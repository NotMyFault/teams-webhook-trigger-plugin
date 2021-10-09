package org.jenkinsci.plugins.teamstrigger.resolvers;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Maps.newHashMap;
import static java.util.logging.Level.INFO;
import static org.jenkinsci.plugins.teamstrigger.ExpressionType.*;

import com.google.common.base.Charsets;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import org.jenkinsci.plugins.teamstrigger.GenericVariable;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class PostContentParameterResolver {
  private static final Logger LOGGER = Logger.getLogger(VariablesResolver.class.getName());

  private final XmlFlattener xmlFlattener = new XmlFlattener();
  private final JsonFlattener jsonFlattener = new JsonFlattener();

  public PostContentParameterResolver() {}

  public Map<String, String> getPostContentParameters(
      final List<GenericVariable> configuredGenericVariables,
      final String incomingPostContent,
      final String textSeparator,
      boolean requestFromTeams) {
    final Map<String, String> resolvedVariables = newHashMap();
    if (configuredGenericVariables != null) {
      for (final GenericVariable gv : configuredGenericVariables) {
        final Map<String, String> resolvedMap =
            this.resolve(incomingPostContent, gv, textSeparator, requestFromTeams);
        final boolean notResolved =
            resolvedMap.isEmpty()
                || resolvedMap.containsKey(gv.getVariableName())
                    && resolvedMap.get(gv.getVariableName()).isEmpty();
        if (notResolved && gv.getDefaultValue() != null) {
          resolvedMap.put(gv.getVariableName(), gv.getDefaultValue());
        }
        resolvedVariables.putAll(resolvedMap);
      }
    }
    return resolvedVariables;
  }

  private Map<String, String> resolve(
      final String incomingPostContent,
      final GenericVariable gv,
      final String textSeparator,
      boolean requestFromTeams) {
    try {
      if (!isNullOrEmpty(incomingPostContent)
          && gv != null
          && gv.getExpression() != null
          && !gv.getExpression().isEmpty()) {
        if (gv.getExpressionType() == JSONPath) {
          return this.resolveJsonPath(incomingPostContent, gv);
        } else if (gv.getExpressionType() == XPath) {
          return this.resolveXPath(incomingPostContent, gv);
        } else if (gv.getExpressionType() == StringPart) {
          return this.resolveStringPart(incomingPostContent, gv, textSeparator, requestFromTeams);
        } else {
          throw new IllegalStateException("Not recognizing " + gv.getExpressionType());
        }
      }
    } catch (final Throwable e) {
      LOGGER.log(
          INFO,
          "Unable to resolve "
              + gv.getVariableName()
              + " with "
              + gv.getExpressionType()
              + " "
              + gv.getExpression()
              + " in\n"
              + incomingPostContent,
          e);
    }
    return new HashMap<>();
  }

  private Map<String, String> resolveJsonPath(
      final String incomingPostContent, final GenericVariable gv) {
    try {
      final Object resolved = JsonPath.read(incomingPostContent, gv.getExpression());
      final Map<String, String> flatterned =
          this.jsonFlattener.flattenJson(gv.getVariableName(), gv.getRegexpFilter(), resolved);
      if (gv.getExpression().trim().equals("$")) {
        flatterned.put(gv.getVariableName(), incomingPostContent);
      }
      return flatterned;
    } catch (final PathNotFoundException e) {
      return new HashMap<>();
    }
  }

  private Map<String, String> resolveXPath(
      final String incomingPostContent, final GenericVariable gv) throws Exception {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    final DocumentBuilder builder = factory.newDocumentBuilder();
    final InputSource inputSource =
        new InputSource(new ByteArrayInputStream(incomingPostContent.getBytes(Charsets.UTF_8)));
    final Document doc = builder.parse(inputSource);
    final XPathFactory xPathfactory = XPathFactory.newInstance();
    final XPath xpath = xPathfactory.newXPath();
    final XPathExpression expr = xpath.compile(gv.getExpression());
    final Object resolved = expr.evaluate(doc, XPathConstants.NODESET);
    return this.xmlFlattener.flatternXmlNode(gv, (NodeList) resolved);
  }

  private Map<String, String> resolveStringPart(
      final String incomingPostContent,
      final GenericVariable gv,
      final String textSeparator,
      boolean requestFromTeams) {
    try {
      final Object resolved = JsonPath.read(incomingPostContent, "$.text");
      final Map<String, String> textValue =
          this.jsonFlattener.flattenJson("text", "$.text", resolved);
      final int index = Integer.parseInt(gv.getExpression().replace("$.", ""));
      String content = textValue.get("text");
      if (content.contains("param:")) {
        String subContent =
            content.substring(content.lastIndexOf("param:") + 6, content.lastIndexOf("\n"));
        content = subContent.replace("</at>", "");
      }
      final String body = content;
      return new HashMap<String, String>() {
        {
          put(
              gv.getVariableName(),
              body.substring(body.indexOf(",") + 1).split(textSeparator)[index - 1]);
        }
      };
    } catch (final PathNotFoundException e) {
      return new HashMap<>();
    }
  }
}

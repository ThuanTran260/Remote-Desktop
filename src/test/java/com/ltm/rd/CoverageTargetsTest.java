package com.ltm.rd;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Keeps the JaCoCo coverage gate honest. JaCoCo silently ignores an {@code include} that matches no
 * class, so a renamed or mistyped class would leave the gate checking nothing. This test compares
 * {@code coverage-targets.txt} with the includes of the one active
 * {@code CLASS / BRANCH / COVEREDRATIO / 0.80} rule of the {@code check} execution in
 * {@code build/plugins} of {@code pom.xml}, and loads every name as a class. The pom is parsed as
 * XML, so names in comments, {@code pluginManagement} or profiles never count.
 */
class CoverageTargetsTest {
    private static final String TARGETS_RESOURCE = "/coverage-targets.txt";
    private static final BigDecimal REQUIRED_MINIMUM = new BigDecimal("0.80");
    private static final String JACOCO_PLUGIN = "/project/build/plugins/plugin"
            + "[groupId='org.jacoco' and artifactId='jacoco-maven-plugin']";

    // ---- the real build ----------------------------------------------------------------------

    @Test
    void targetsFileAndGateRuleNameTheSameClasses() throws Exception {
        List<String> fromFile = targetsFile();
        Set<String> fromRule = ruleIncludes(parse(Files.readString(pomPath(), StandardCharsets.UTF_8)));

        assertEquals(fromFile.size(), new LinkedHashSet<>(fromFile).size(), "duplicate in coverage-targets.txt");
        assertEquals(new LinkedHashSet<>(fromFile), fromRule,
                "coverage-targets.txt and the includes of the JaCoCo check rule in pom.xml must list the same classes");
    }

    @Test
    void everyTargetIsALoadableClassWithoutWildcards() throws Exception {
        ClassLoader loader = CoverageTargetsTest.class.getClassLoader();
        Set<String> names = new LinkedHashSet<>(targetsFile());
        names.addAll(ruleIncludes(parse(Files.readString(pomPath(), StandardCharsets.UTF_8))));
        assertFalse(names.isEmpty());
        for (String name : names) {
            assertFalse(name.contains("*") || name.contains("?"), "exact class names only, no wildcard: " + name);
            assertDoesNotThrow(() -> Class.forName(name, false, loader), "not a class on the test classpath: " + name);
        }
    }

    @Test
    void jacocoReportAndAgentAreNotFilteredByClassName() throws Exception {
        Document pom = parse(Files.readString(pomPath(), StandardCharsets.UTF_8));
        XPath xpath = XPathFactory.newInstance().newXPath();
        // a plugin-level or non-check include/exclude would hide target classes from report and analysis
        for (String filter : new String[] {"includes", "excludes"}) {
            assertEquals(0, nodes(xpath, JACOCO_PLUGIN + "/configuration/" + filter, pom).getLength(),
                    "plugin-level <" + filter + "> in jacoco-maven-plugin");
            assertEquals(0, nodes(xpath, JACOCO_PLUGIN + "/executions/execution[goals/goal!='check']"
                    + "/configuration/" + filter, pom).getLength(), "<" + filter + "> in prepare-agent/report");
        }
    }

    // ---- the parser itself, on small fixture poms --------------------------------------------

    @Test
    void ruleIncludesIgnoresCommentsPluginManagementAndProfiles() throws Exception {
        String pom = """
                <project>
                  <!-- <include>com.example.InAComment</include> -->
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId>
                          <executions><execution><goals><goal>check</goal></goals>
                            <configuration><rules><rule><element>CLASS</element>
                              <includes><include>com.example.InPluginManagement</include></includes>
                              <limits><limit><counter>BRANCH</counter><value>COVEREDRATIO</value>
                                <minimum>0.80</minimum></limit></limits>
                            </rule></rules></configuration>
                          </execution></executions>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                    <plugins>
                      <plugin>
                        <groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId>
                        <executions><execution><goals><goal>check</goal></goals>
                          <configuration><rules><rule><element>CLASS</element>
                            <includes><include> com.example.Active </include></includes>
                            <limits><limit><counter>BRANCH</counter><value>COVEREDRATIO</value>
                              <minimum>0.8</minimum></limit></limits>
                          </rule></rules></configuration>
                        </execution></executions>
                      </plugin>
                    </plugins>
                  </build>
                  <profiles><profile><build><plugins><plugin>
                    <groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId>
                    <executions><execution><goals><goal>check</goal></goals>
                      <configuration><rules><rule><element>CLASS</element>
                        <includes><include>com.example.InAProfile</include></includes>
                        <limits><limit><counter>BRANCH</counter><value>COVEREDRATIO</value>
                          <minimum>0.80</minimum></limit></limits>
                      </rule></rules></configuration>
                    </execution></executions>
                  </plugin></plugins></build></profile></profiles>
                </project>
                """;

        assertEquals(Set.of("com.example.Active"), ruleIncludes(parse(pom)));
    }

    @Test
    void ruleWithAnotherElementCounterValueOrMinimumDoesNotCount() throws Exception {
        assertThrows(AssertionError.class, () -> ruleIncludes(parse(pomWithRule("BUNDLE", "BRANCH", "COVEREDRATIO", "0.80"))));
        assertThrows(AssertionError.class, () -> ruleIncludes(parse(pomWithRule("CLASS", "LINE", "COVEREDRATIO", "0.80"))));
        assertThrows(AssertionError.class, () -> ruleIncludes(parse(pomWithRule("CLASS", "BRANCH", "MISSEDCOUNT", "0.80"))));
        assertThrows(AssertionError.class, () -> ruleIncludes(parse(pomWithRule("CLASS", "BRANCH", "COVEREDRATIO", "0.70"))));
        assertEquals(Set.of("com.example.A"), ruleIncludes(parse(pomWithRule("CLASS", "BRANCH", "COVEREDRATIO", "0.80"))));
    }

    @Test
    void twoMatchingRulesAreRejected() throws Exception {
        String rule = "<rule><element>CLASS</element><includes><include>com.example.A</include></includes><limits><limit>"
                + "<counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.80</minimum></limit></limits></rule>";
        String pom = pomWithRules(rule + rule);

        assertThrows(AssertionError.class, () -> ruleIncludes(parse(pom)));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String pomWithRule(String element, String counter, String value, String minimum) {
        return pomWithRules("<rule><element>" + element + "</element><includes><include>com.example.A</include></includes>"
                + "<limits><limit><counter>" + counter + "</counter><value>" + value + "</value><minimum>" + minimum
                + "</minimum></limit></limits></rule>");
    }

    private static String pomWithRules(String rules) {
        return "<project><build><plugins><plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId>"
                + "<executions><execution><goals><goal>check</goal></goals><configuration><rules>" + rules
                + "</rules></configuration></execution></executions></plugin></plugins></build></project>";
    }

    /** The include set of the single active CLASS/BRANCH/COVEREDRATIO/0.80 rule of the one {@code check} execution. */
    static Set<String> ruleIncludes(Document pom) throws Exception {
        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList plugins = nodes(xpath, JACOCO_PLUGIN, pom);
        assertEquals(1, plugins.getLength(), "exactly one jacoco-maven-plugin in build/plugins");
        NodeList checks = nodes(xpath, "executions/execution[goals/goal='check']", plugins.item(0));
        assertEquals(1, checks.getLength(), "exactly one execution of the check goal");
        NodeList candidates = nodes(xpath,
                "configuration/rules/rule[element='CLASS'][limits/limit[counter='BRANCH' and value='COVEREDRATIO']]",
                checks.item(0));
        List<Node> matching = new ArrayList<>();
        for (int i = 0; i < candidates.getLength(); i++) {
            NodeList limits = nodes(xpath, "limits/limit[counter='BRANCH' and value='COVEREDRATIO']", candidates.item(i));
            for (int j = 0; j < limits.getLength(); j++) {
                String minimum = (String) xpath.evaluate("minimum", limits.item(j), XPathConstants.STRING);
                if (!minimum.isBlank() && new BigDecimal(minimum.trim()).compareTo(REQUIRED_MINIMUM) == 0) {
                    matching.add(candidates.item(i));
                }
            }
        }
        assertEquals(1, matching.size(), "exactly one CLASS/BRANCH/COVEREDRATIO rule with minimum 0.80");
        NodeList includes = nodes(xpath, "includes/include", matching.get(0));
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < includes.getLength(); i++) {
            String name = includes.item(i).getTextContent().trim();
            assertFalse(name.isEmpty(), "empty <include>");
            assertTrue(names.add(name), "duplicate <include>: " + name);
        }
        return names;
    }

    private static NodeList nodes(XPath xpath, String expression, Object context) throws Exception {
        return (NodeList) xpath.evaluate(expression, context, XPathConstants.NODESET);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(false); // the pom's default namespace stays out of the XPath
        try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static Path pomPath() {
        return Path.of(System.getProperty("basedir", System.getProperty("user.dir")), "pom.xml");
    }

    private static List<String> targetsFile() throws IOException {
        try (InputStream in = CoverageTargetsTest.class.getResourceAsStream(TARGETS_RESOURCE)) {
            assertNotNull(in, TARGETS_RESOURCE + " is missing from the test resources");
            List<String> names = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String name = line.trim();
                if (!name.isEmpty()) names.add(name);
            }
            assertFalse(names.isEmpty(), TARGETS_RESOURCE + " lists no class");
            return names;
        }
    }
}

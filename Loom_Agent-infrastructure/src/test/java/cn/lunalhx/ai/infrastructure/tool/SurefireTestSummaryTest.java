package cn.lunalhx.ai.infrastructure.tool;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SurefireTestSummaryTest {

    @Test
    @SuppressWarnings("unchecked")
    public void shouldExtractFailureExpectedActualAndStderr() throws Exception {
        Path reports = Files.createTempDirectory("surefire-summary");
        Files.writeString(reports.resolve("TEST-DemoTest.xml"), """
                <testsuite name="DemoTest" tests="1" failures="1" errors="0" skipped="0">
                  <testcase classname="com.example.DemoTest" name="deductsStock">
                    <failure type="org.junit.ComparisonFailure"
                      message="expected:&lt;7&gt; but was:&lt;10&gt;">stack</failure>
                    <system-err>diagnostic stderr</system-err>
                  </testcase>
                </testsuite>
                """);

        Map<String, Object> summary = SurefireTestSummary.read(reports);
        List<Map<String, Object>> failures =
                (List<Map<String, Object>>) summary.get("failureDetails");

        assertEquals(1, summary.get("tests"));
        assertEquals(Boolean.FALSE, summary.get("passed"));
        assertEquals("com.example.DemoTest", failures.get(0).get("className"));
        assertEquals("deductsStock", failures.get(0).get("testName"));
        assertEquals("7", failures.get(0).get("expected"));
        assertEquals("10", failures.get(0).get("actual"));
        assertEquals("diagnostic stderr", failures.get(0).get("stderr"));
        assertTrue(SurefireTestSummary.render(summary).startsWith("[test_result]"));
    }

    @Test
    public void executionSummaryShouldReadCurrentMultiModuleReportsOnly() throws Exception {
        Path workspace = Files.createTempDirectory("surefire-workspace");
        Path current = Files.createDirectories(
                workspace.resolve("module-a/target/surefire-reports"));
        Path stale = Files.createDirectories(
                workspace.resolve("module-b/target/failsafe-reports"));
        long startedAt = System.currentTimeMillis();
        Files.writeString(current.resolve("TEST-Current.xml"), """
                <testsuite tests="2" failures="0" errors="0" skipped="0"/>
                """);
        Path staleReport = stale.resolve("TEST-Stale.xml");
        Files.writeString(staleReport, """
                <testsuite tests="99" failures="99" errors="0" skipped="0"/>
                """);
        Files.setLastModifiedTime(
                staleReport, FileTime.fromMillis(startedAt - 10_000L));

        Map<String, Object> summary =
                SurefireTestSummary.readForExecution(workspace, startedAt);

        assertEquals(2, summary.get("tests"));
        assertEquals(0, summary.get("failures"));
    }

    @Test
    public void externalEntityReportShouldBeRejected() throws Exception {
        Path reports = Files.createTempDirectory("surefire-xxe");
        Files.writeString(reports.resolve("TEST-Xxe.xml"), """
                <!DOCTYPE testsuite [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <testsuite tests="1" failures="1" errors="0" skipped="0">
                  <testcase name="x"><failure message="&xxe;"/></testcase>
                </testsuite>
                """);

        Map<String, Object> summary = SurefireTestSummary.read(reports);

        assertEquals(Boolean.FALSE, summary.get("available"));
        assertTrue(summary.containsKey("parseError"));
    }
}

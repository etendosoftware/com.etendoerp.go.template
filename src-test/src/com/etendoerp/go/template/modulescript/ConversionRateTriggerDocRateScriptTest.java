/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.template.modulescript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openbravo.database.ConnectionProvider;

/**
 * Unit tests for {@code ConversionRateTriggerDocRateScript}, the module script that replaces the
 * body of core's {@code C_CONVERSION_RATE_TRG} so entries posted with a document-level rate do not
 * lock a general conversion rate.
 *
 * <p>Module scripts are compiled into {@code build/classes} by {@code compile.modulescript}, which
 * is not on the test classpath. So this class compiles the script's real source in memory against
 * the test classpath and drives it with a mocked {@link ConnectionProvider}, as
 * {@code EnsureLegacyPlanScriptTest} does in com.etendoerp.go. The trigger's behaviour on real
 * data (allowed vs blocked cases) needs PostgreSQL and is not covered here.</p>
 *
 * @covers com.etendoerp.go.template.modulescript.ConversionRateTriggerDocRateScript
 */
class ConversionRateTriggerDocRateScriptTest {

  private static final Path SCRIPT_RELATIVE = Paths.get("src-util", "modulescript", "src", "com",
      "etendoerp", "go", "template", "modulescript", "ConversionRateTriggerDocRateScript.java");

  @TempDir
  static Path classesDir;

  private static Class<?> scriptClass;

  @BeforeAll
  static void compileScript() throws Exception {
    Path here = Paths.get("");
    Optional<Path> root = List.of(here, here.resolve(Paths.get("modules", "com.etendoerp.go.template")))
        .stream()
        .filter(candidate -> Files.isRegularFile(candidate.resolve(SCRIPT_RELATIVE)))
        .findFirst();
    Assumptions.assumeTrue(root.isPresent(),
        () -> "Skipping: com.etendoerp.go.template module root not found from "
            + here.toAbsolutePath());
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Assumptions.assumeTrue(compiler != null, "Skipping: no system Java compiler (JRE only)");

    int status = compiler.run(null, null, null, "-proc:none", "-encoding", "UTF-8", "-classpath",
        System.getProperty("java.class.path"), "-d", classesDir.toString(),
        root.orElseThrow().resolve(SCRIPT_RELATIVE).toString());
    assertEquals(0, status, "ConversionRateTriggerDocRateScript.java must compile");

    URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() },
        ConversionRateTriggerDocRateScriptTest.class.getClassLoader());
    scriptClass = Class.forName(
        "com.etendoerp.go.template.modulescript.ConversionRateTriggerDocRateScript", true, loader);
  }

  @Test
  void replacesTheFunctionWithOneStatement() throws Exception {
    ConnectionProvider cp = mock(ConnectionProvider.class);
    PreparedStatement replace = mock(PreparedStatement.class);
    String sql = constant("REPLACE_FUNCTION_SQL");
    when(cp.getPreparedStatement(sql)).thenReturn(replace);

    replaceFunction(cp);

    verify(replace).execute();
    verify(replace).close();
  }

  @Test
  void replacesOnlyTheFunctionNotTheTrigger() throws Exception {
    String sql = constant("REPLACE_FUNCTION_SQL");

    assertTrue(sql.startsWith("CREATE OR REPLACE FUNCTION c_conversion_rate_trg()"));
    assertTrue(!sql.toUpperCase().contains("DROP TRIGGER"),
        "the trigger object must stay core's; only its function body is replaced");
  }

  @Test
  void bothLockingBranchesSkipEntriesWithADocumentRate() throws Exception {
    String sql = constant("REPLACE_FUNCTION_SQL");
    String exclusion = constant("NO_DOCUMENT_RATE");

    int updateBranch = sql.indexOf("IF TG_OP = 'UPDATE' THEN");
    int deleteBranch = sql.indexOf("IF TG_OP = 'DELETE' THEN\n");
    assertTrue(updateBranch > 0 && deleteBranch > updateBranch);
    assertTrue(sql.indexOf(exclusion, updateBranch) > updateBranch
        && sql.indexOf(exclusion, updateBranch) < deleteBranch, "UPDATE branch excludes them");
    assertTrue(sql.indexOf(exclusion, deleteBranch) > deleteBranch, "DELETE branch excludes them");
    assertEquals(2, sql.split("@20506@", -1).length - 1, "both branches still raise @20506@");
  }

  @Test
  void documentRateMappingMatchesAcctServer() throws Exception {
    String exclusion = constant("NO_DOCUMENT_RATE");

    assertTrue(exclusion.contains("d.c_currency_id = f.c_currency_id"));
    assertTrue(exclusion.contains("d.c_currency_id_to = s.c_currency_id"));
    assertTrue(exclusion.contains("d.isactive = 'Y'"));
    assertTrue(exclusion.contains("f.ad_table_id = '318' AND d.c_invoice_id = f.record_id"));
    assertTrue(exclusion.contains("'D1A97202E832470285C9B1EB026D54E2'"));
    assertTrue(exclusion.contains("d.fin_payment_id = f.record_id"));
    assertTrue(exclusion.contains("'4D8C3B3C31D1410DA046140C9F024D17'"));
    assertTrue(exclusion.contains("d.aprm_finacc_transaction_v_id = f.record_id"),
        "AcctServer reads the APRM view column for transactions");
    assertTrue(exclusion.contains("f.ad_table_id = '224' AND d.gl_journal_id = f.record_id"));
  }

  private static String constant(String name) throws Exception {
    Field field = scriptClass.getDeclaredField(name);
    field.setAccessible(true);
    return (String) field.get(null);
  }

  private static void replaceFunction(ConnectionProvider cp) throws Exception {
    Object script = scriptClass.getDeclaredConstructor().newInstance();
    Method method = scriptClass.getDeclaredMethod("replaceFunction", ConnectionProvider.class);
    method.setAccessible(true);
    try {
      method.invoke(script, cp);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }
}

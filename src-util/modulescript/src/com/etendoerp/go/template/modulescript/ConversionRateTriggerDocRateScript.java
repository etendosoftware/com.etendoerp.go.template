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

import java.sql.PreparedStatement;

import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;

/**
 * ETP-5860 — replaces the body of core's {@code C_CONVERSION_RATE_TRG} so that accounting entries
 * posted with a document-level exchange rate no longer lock a general conversion rate.
 *
 * <p><b>The problem.</b> Core's trigger raises {@code @20506@} ("rate already used") when a rate is
 * updated or deleted and any {@code FACT_ACCT} row of the same currency pair, inside the rate's
 * window, would fall outside the new window (or would see a different multiply rate). It looks only
 * at currency and accounting date: {@code FACT_ACCT} does not record which rate an entry was
 * converted with. So an entry whose document carries its own rate in the Exchange Rate tab
 * ({@code C_Conversion_Rate_Document}) still locks the general rate, although changing that rate
 * cannot affect it. On 2026-10-21 a single such invoice blocked the nightly System USD-&gt;EUR
 * download for every tenant.</p>
 *
 * <p><b>The rule.</b> An entry is ignored when its document has an active document-level rate for
 * the same pair. The table/column mapping is the one {@code AcctServer.getConversionRateDoc} uses
 * when posting, so "has a document rate" means exactly "was posted with the document rate":
 * {@code C_Invoice}, {@code FIN_Payment}, {@code FIN_Finacc_Transaction} and {@code GL_Journal}.
 * Entries of any other table, or without a document rate, keep locking the rate as before. The
 * same rule applies to the UPDATE and the DELETE branches. Overlapping rates are still rejected by
 * {@code ConversionRateEventHandler} ({@code @20504@}), which this script does not touch.</p>
 *
 * <p><b>When it runs.</b> On every {@code update.database} (no execution limits; {@code CREATE OR
 * REPLACE FUNCTION} is idempotent), after DBSM has applied the model. DBSM recreates the function
 * from core's XML whenever it differs, so a one-off execution would be reverted by the next
 * update. Only the function is replaced: the trigger object and its name stay core's.</p>
 *
 * <p><b>The compiled class is committed</b>: {@code update.database} only runs module scripts that
 * are already compiled. After ANY edit to this source, re-run
 * {@code ./gradlew compile.modulescript -Dmodule=com.etendoerp.go.template} and
 * {@code git add -f build/classes/com/etendoerp/go/template/modulescript/ConversionRateTriggerDocRateScript.class};
 * a stale class keeps running the old body with no warning.</p>
 *
 * <p><b>Retirement.</b> If core's trigger gets this rule, delete this script (source AND class) in
 * the same change, or it keeps overwriting core's newer body.</p>
 */
public class ConversionRateTriggerDocRateScript extends ModuleScript {

  /**
   * Excludes the entry {@code f} (posted in schema {@code s}) when its document carries an active
   * document-level rate for the same pair. Table ids are {@code AcctServer.TABLEID_*}. For a
   * financial account transaction the column is {@code APRM_FINACC_TRANSACTION_V_ID}, the one
   * {@code AcctServer} reads; {@code C_CONVERSION_RATE_DOCUMENT_TRG} copies it from
   * {@code FIN_FINACC_TRANSACTION_ID} on insert, so both hold the same id.
   */
  static final String NO_DOCUMENT_RATE = """
             AND NOT EXISTS (
               SELECT 1
                 FROM c_conversion_rate_document d
                WHERE d.c_currency_id = f.c_currency_id
                  AND d.c_currency_id_to = s.c_currency_id
                  AND d.isactive = 'Y'
                  AND (   (f.ad_table_id = '318' AND d.c_invoice_id = f.record_id)
                       OR (f.ad_table_id = 'D1A97202E832470285C9B1EB026D54E2'
                           AND d.fin_payment_id = f.record_id)
                       OR (f.ad_table_id = '4D8C3B3C31D1410DA046140C9F024D17'
                           AND d.aprm_finacc_transaction_v_id = f.record_id)
                       OR (f.ad_table_id = '224' AND d.gl_journal_id = f.record_id)))
      """;

  static final String REPLACE_FUNCTION_SQL = """
      CREATE OR REPLACE FUNCTION c_conversion_rate_trg()
        RETURNS trigger
        LANGUAGE plpgsql
      AS $BODY$ DECLARE
        v_Count NUMERIC;
      BEGIN
        IF AD_isTriggerEnabled()='N' THEN
          IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
        END IF;

        IF (TG_OP = 'INSERT' OR TG_OP = 'UPDATE') THEN
          IF (new.ValidTo IS NULL) THEN
            -- If there is no end date, set it to X
            new.ValidTo := TO_DATE('31-12-9999', 'DD-MM-YYYY');
          END IF;
          -- Check for invalid dates
          IF new.ValidTo < new.ValidFrom THEN
            RAISE EXCEPTION '%', '@20505@'; --OBTG:-20000--
          END IF;
        END IF;

        IF TG_OP = 'UPDATE' THEN
          -- Check for changes of used rates (ETP-5860: entries posted with a document rate do not count)
          SELECT count(*)
            INTO v_Count
            FROM fact_acct f, c_acctschema s
           WHERE f.c_acctschema_id = s.c_acctschema_id
             AND f.c_currency_id = old.C_Currency_ID
             AND s.c_currency_id = old.C_Currency_ID_TO
             AND f.dateacct BETWEEN old.ValidFrom AND old.ValidTo
             AND ((NOT (f.dateacct BETWEEN new.ValidFrom AND new.ValidTo))
                  OR (new.C_Currency_ID != old.C_Currency_ID)
                  OR (new.C_Currency_ID_To != old.C_Currency_ID_To)
                  OR (new.MultiplyRate != old.MultiplyRate))
      """ + NO_DOCUMENT_RATE + """
          ;
          IF v_Count > 0 THEN
            RAISE EXCEPTION '%', '@20506@'; --OBTG:-20000--
          END IF;
        END IF;

        IF TG_OP = 'DELETE' THEN
          -- ETP-5860: entries posted with a document rate do not count
          SELECT count(*)
            INTO v_Count
            FROM fact_acct f, c_acctschema s
           WHERE f.c_acctschema_id = s.c_acctschema_id
             AND f.c_currency_id = old.C_Currency_ID
             AND s.c_currency_id = old.C_Currency_ID_TO
             AND f.dateacct BETWEEN old.ValidFrom AND old.ValidTo
      """ + NO_DOCUMENT_RATE + """
          ;
          IF v_Count > 0 THEN
            RAISE EXCEPTION '%', '@20506@'; --OBTG:-20000--
          END IF;
        END IF;

        IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
      END
      ; $BODY$
      """;

  @Override
  public void execute() {
    try {
      replaceFunction(getConnectionProvider());
    } catch (Exception e) {
      handleError(e);
    }
  }

  void replaceFunction(ConnectionProvider cp) throws Exception {
    try (PreparedStatement ps = cp.getPreparedStatement(REPLACE_FUNCTION_SQL)) {
      ps.execute();
    }
    log4j.info("Replaced c_conversion_rate_trg(): entries posted with a document rate no longer "
        + "lock a conversion rate (ETP-5860)");
  }
}

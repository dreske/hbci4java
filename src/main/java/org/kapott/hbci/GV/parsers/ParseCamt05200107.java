/**********************************************************************
 *
 * This file is part of HBCI4Java.
 * Copyright (c) 2018 Olaf Willuhn
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *
 **********************************************************************/

package org.kapott.hbci.GV.parsers;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import javax.xml.bind.JAXB;

import org.kapott.hbci.GV.SepaUtil;
import org.kapott.hbci.GV_Result.GVRKUms.BTag;
import org.kapott.hbci.GV_Result.GVRKUms.UmsLine;
import org.kapott.hbci.manager.HBCIUtils;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.AccountIdentification4Choice;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.AccountReport22;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.ActiveOrHistoricCurrencyAndAmount;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.BankToCustomerAccountReportV07;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.BankTransactionCodeStructure4;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.BranchAndFinancialInstitutionIdentification5;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.CashAccount24;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.CashAccount36;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.CashBalance8;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.CreditDebitCode;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.DateAndDateTime2Choice;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.Document;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.EntryDetails8;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.EntryTransaction9;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.FinancialInstitutionIdentification8;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.GenericPersonIdentification1;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.Party34Choice;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.Party35Choice;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.PartyIdentification125;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.PersonIdentification13;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.Purpose2Choice;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.ReportEntry9;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.TransactionAgents4;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.TransactionParties4;
import org.kapott.hbci.sepa.jaxb.camt_052_001_07.TransactionReferences3;
import org.kapott.hbci.structures.Konto;
import org.kapott.hbci.structures.Saldo;
import org.kapott.hbci.structures.Value;

/**
 * Parser zum Lesen von Umsaetzen im CAMT.052 Format in Version 001.07.
 */
public class ParseCamt05200107 extends AbstractCamtParser
{
    /**
     * @see org.kapott.hbci.GV.parsers.ISEPAParser#parse(java.io.InputStream, java.lang.Object)
     */
    @Override
    public void parse(InputStream xml, List<BTag> tage)
    {
        
        Document doc = JAXB.unmarshal(xml, Document.class);
        BankToCustomerAccountReportV07 container = doc.getBkToCstmrAcctRpt();

        // Dokument leer
        if (container == null)
        {
            HBCIUtils.log("camt document empty",HBCIUtils.LOG_WARN);
            return;
        }

        // Enthaelt per Definition genau einen Report von einem Buchungstag
        List<AccountReport22> reports = container.getRpt();
        if (reports == null || reports.size() == 0)
        {
            HBCIUtils.log("camt document empty",HBCIUtils.LOG_WARN);
            return;
        }
        
        // Per Definition enthaelt die Datei beim CAMT-Abruf zwar genau einen Buchungstag.
        // Da wir aber eine passende Datenstruktur haben, lesen wir mehr ein, falls
        // mehr vorhanden sind. Dann koennen wird den Parser spaeter auch nutzen,
        // um CAMT-Dateien aus anderen Quellen zu lesen.
        for (AccountReport22 report:reports)
        {
            ////////////////////////////////////////////////////////////////////
            // Kopf des Buchungstages
            BTag tag = this.createDay(report);
            tage.add(tag);
            //
            ////////////////////////////////////////////////////////////////////

            ////////////////////////////////////////////////////////////////////
            // Die einzelnen Buchungen
            BigDecimal saldo = tag.start.value.getBigDecimalValue();
            
            for (ReportEntry9 entry:report.getNtry())
            {
                UmsLine line = this.createLine(entry,saldo);
                if (line != null)
                {
                    tag.lines.add(line);
                    
                    // Saldo fortschreiben
                    saldo = line.saldo.value.getBigDecimalValue();
                }
            }
            //
            ////////////////////////////////////////////////////////////////////

            ////////////////////////////////////////////////////////////////////
            // Apo-Bank Sonderbehandlung: Wenn wir keinen Start-Saldo, dafuer aber einen End-Saldo haben,
            // rechnen wir rueckwaerts von dem
            if (tag.start.timestamp == null && tag.end.timestamp != null)
            {
                BigDecimal endSaldo = tag.end.value.getBigDecimalValue();
                int n = tag.lines.size();
                while (n > 0)
                {
                    UmsLine line = tag.lines.get(--n);
                    line.saldo.value.setValue(endSaldo);
                    endSaldo = endSaldo.subtract(line.value.getBigDecimalValue());
                }
            }
            //
            ////////////////////////////////////////////////////////////////////
        }
    }

    /**
     * Erzeugt eine einzelne Umsatzbuchung.
     * @param entry der Entry aus der CAMT-Datei.
     * @param currSaldo der aktuelle Saldo vor dieser Buchung.
     * @return die Umsatzbuchung.
     */
    private UmsLine createLine(ReportEntry9 entry, BigDecimal currSaldo)
    {
        UmsLine line = new UmsLine();
        line.isSepa = true;
        line.isCamt = true;
        line.other = new Konto();

        ////////////////////////////////////////////////////////////////////////
        // Betrag
        ActiveOrHistoricCurrencyAndAmount amt = entry.getAmt();
        BigDecimal bd = amt.getValue() != null ? amt.getValue() : BigDecimal.ZERO;
        line.value = new Value(this.checkDebit(bd,entry.getCdtDbtInd()));
        line.value.setCurr(amt.getCcy());
        //
        ////////////////////////////////////////////////////////////////////////
        
        ////////////////////////////////////////////////////////////////////////
        // Storno-Kennzeichen
        // Laut Spezifikation kehrt sich bei Stornobuchungen im Gegensatz zu MT940
        // nicht das Vorzeichen um. Der Betrag bleibt also gleich
        line.isStorno = entry.isRvslInd() != null ? entry.isRvslInd().booleanValue() : false;
        //
        ////////////////////////////////////////////////////////////////////////


        ////////////////////////////////////////////////////////////////////////
        // Buchungs- und Valuta-Datum
        DateAndDateTime2Choice bdate = entry.getBookgDt();
        line.bdate = bdate != null ? SepaUtil.toDate(bdate.getDt()) : null;
        
        DateAndDateTime2Choice vdate = entry.getValDt();
        line.valuta = vdate != null ? SepaUtil.toDate(vdate.getDt()) : null;
        
        // Wenn einer von beiden Werten fehlt, uebernehmen wir dort den jeweils anderen
        if (line.bdate == null) line.bdate = line.valuta;
        if (line.valuta == null) line.valuta = line.bdate;
        //
        ////////////////////////////////////////////////////////////////////////
        
        ////////////////////////////////////////////////////////////////////////
        // Saldo
        line.saldo = new Saldo();
        line.saldo.value = new Value(currSaldo.add(line.value.getBigDecimalValue()));
        line.saldo.value.setCurr(line.value.getCurr());
        line.saldo.timestamp = line.bdate;
        //
        ////////////////////////////////////////////////////////////////////////
        
        ////////////////////////////////////////////////////////////////////////
        // Art und Kundenreferenz
        line.text = trim(entry.getAddtlNtryInf());
        line.customerref = trim(entry.getAcctSvcrRef());
        //
        ////////////////////////////////////////////////////////////////////////
        
        final List<EntryDetails8> details = entry.getNtryDtls();
        if (details.size() == 0)
        {
          // Wir packen in dem Fall den Info-Text noch zusätzlich in den Verwendungszweck
          line.usage.add(trim(entry.getAddtlNtryInf()));
          return line;
        }
        
        // Das Schema sieht zwar mehrere Detail-Elemente vor, ich wuesste
        // aber ohnehin nicht, wie man das sinnvoll mappen koennte 
        EntryDetails8 detail = details.get(0);
        
        List<EntryTransaction9> txList = detail.getTxDtls();
        if (txList.size() == 0)
            return null;
        
        // Checken, ob es Soll- oder Habenbuchung ist
        boolean haben = entry.getCdtDbtInd() != null && entry.getCdtDbtInd() == CreditDebitCode.CRDT;
        
        // ditto
        EntryTransaction9 tx = txList.get(0);

        // Ist es eine Rueckbuchung?
        boolean rueckbuchung = tx.getRtrInf() != null && tx.getRtrInf().getRsn() != null && tx.getRtrInf().getRsn().getCd() != null && tx.getRtrInf().getRsn().getCd().length() > 0;
        if (rueckbuchung) { // Bei Rueckbuchung tauschen wir Creditor und Debitor
            haben = !haben;
            if (tx.getAmtDtls() != null && tx.getAmtDtls().getInstdAmt() != null && tx.getAmtDtls().getInstdAmt().getAmt() != null) {
                // Ursprungsbetrag bei Rückbuchungen
                line.orig_value = new Value(tx.getAmtDtls().getInstdAmt().getAmt().getValue(), tx.getAmtDtls().getInstdAmt().getAmt().getCcy());
            }

            if (tx.getRtrInf().getAddtlInf() != null) {
                // Grund für Rückbuchung
                line.additional = String.join(",", trim(tx.getRtrInf().getAddtlInf()));
            }
        }
        
        ////////////////////////////////////////////////////////////////////////
        // Buchungs-ID
        TransactionReferences3 ref = tx.getRefs();
        if (ref != null)
        {
            line.id = trim(ref.getPrtry() != null && ref.getPrtry().size() > 0 ? ref.getPrtry().get(0).getRef() : null);
            // einige Banken verwenden das Account Servicer Reference als eindeutigen Identifier
            if(line.id==null) {
                line.id = Optional.ofNullable(entry.getAcctSvcrRef()).orElse(ref.getAcctSvcrRef());
            }
            line.endToEndId = trim(ref.getEndToEndId());
            line.mandateId = trim(ref.getMndtId());
        }
        ////////////////////////////////////////////////////////////////////////
        
        ////////////////////////////////////////////////////////////////////////
        // Gegenkonto: IBAN + Name
        TransactionParties4 other = tx.getRltdPties();
        if (other != null)
        {
            CashAccount24 acc = haben ? other.getDbtrAcct() : other.getCdtrAcct();
            AccountIdentification4Choice id = acc != null ? acc.getId() : null;
            line.other.iban = trim(id != null ? id.getIBAN() : null);
            
            Party35Choice party = haben ? other.getDbtr() : other.getCdtr();
            PartyIdentification125 pi = party != null ? party.getPty() : null;
            line.other.name = trim(pi != null ? pi.getNm() : null);

            //GläubigerID
            Party34Choice id2 = pi != null ? pi.getId() : null;
            PersonIdentification13 prvtId = id2 != null ? id2.getPrvtId() : null;
            List<GenericPersonIdentification1> othr = prvtId != null ? prvtId.getOthr() : null;
            GenericPersonIdentification1 genericPersonIdentification1 = (othr != null && !othr.isEmpty())? othr.get(0) : null;
            line.other.creditorid = trim(genericPersonIdentification1 != null ? genericPersonIdentification1.getId() : null);

            // Abweichender Name, falls vorhanden
            party = haben ? other.getUltmtDbtr() : other.getUltmtCdtr();
            pi = party != null ? party.getPty() : null;
            line.other.name2 = trim(pi != null ? pi.getNm() : null);
        }
        //
        ////////////////////////////////////////////////////////////////////////
            
        ////////////////////////////////////////////////////////////////////////
        // Gegenkonto: BIC
        TransactionAgents4 banks = tx.getRltdAgts();
        if (banks != null)
        {
            BranchAndFinancialInstitutionIdentification5 bank = haben ? banks.getDbtrAgt() : banks.getCdtrAgt();
            FinancialInstitutionIdentification8 bic = bank != null ? bank.getFinInstnId() : null;
            line.other.bic = trim(bic != null ? bic.getBICFI() : null);
        }
        //
        ////////////////////////////////////////////////////////////////////////
        
        ////////////////////////////////////////////////////////////////////////
        // Verwendungszweck
        List<String> usages = tx.getRmtInf() != null ? tx.getRmtInf().getUstrd() : null;
        if (usages != null && usages.size() > 0)
            line.usage.addAll(trim(usages));
        //
        ////////////////////////////////////////////////////////////////////////

        ////////////////////////////////////////////////////////////////////////
        // Primanota, GV-Code und GV-Code-Ergaenzung
        // Ich weiss nicht, ob das bei allen Banken so codiert ist.
        // Bei der Sparkasse ist es jedenfalls so.
        BankTransactionCodeStructure4 b = tx.getBkTxCd();
        String code = (b != null && b.getPrtry() != null) ? b.getPrtry().getCd() : null;
        if (code != null && code.contains("+"))
        {
            String[] parts = code.split("\\+");
            if (parts.length == 4)
            {
                line.gvcode    = parts[1];
                line.primanota = parts[2];
                line.addkey    = parts[3];
            }
            else if (parts.length == 3)
            {
                line.gvcode    = parts[0];
                line.primanota = parts[1];
                line.addkey    = parts[2];
            }
        }
        //
        ////////////////////////////////////////////////////////////////////////

        ////////////////////////////////////////////////////////////////////////
        // Purpose-Code
        Purpose2Choice purp = tx.getPurp();
        line.purposecode = trim(purp != null ? purp.getCd() : null);
        //
        ////////////////////////////////////////////////////////////////////////
        
        return line;
    }
    
    
    /**
     * Erzeugt einen neuen Buchungstag.
     * @param report der Report.
     * @return der erzeugte Buchungstag.
     */
    private BTag createDay(AccountReport22 report)
    {
        BTag tag = new BTag();
        tag.starttype = 'F';
        tag.endtype = 'F';

        // Achtung - die folgenden beiden Werte duerfen nicht NULL sein - auch wenn wir keinen Saldo haben.
        // Der Aufrufer verlaesst sich darauf. Wuerde dort sonst eine NPE ausloesen
        tag.start = new Saldo();
        tag.end = new Saldo();

        ////////////////////////////////////////////////////////////////
        // Start- un End-Saldo ermitteln
        final long day = 24 * 60 * 60 * 1000L;

        if(report.getBal().size()>0){
            CashBalance8 firstBal = report.getBal().get(0);
            String firstCode = firstBal.getTp().getCdOrPrtry().getCd();
            if(firstCode.equalsIgnoreCase("PRCD") || firstCode.equalsIgnoreCase("ITBD") || firstCode.equalsIgnoreCase("OPBD")) {
                tag.start.value = new Value(this.checkDebit(firstBal.getAmt().getValue(),firstBal.getCdtDbtInd()));
                tag.start.value.setCurr(firstBal.getAmt().getCcy());
                if(firstCode.equalsIgnoreCase("PRCD") ){
                    //  Wir erhoehen noch das Datum um einen Tag, damit aus dem
                    // Schlusssaldo des Vortages der Startsaldo des aktuellen Tages wird.
                    tag.start.timestamp = new Date(SepaUtil.toDate(firstBal.getDt().getDt()).getTime() + day);
                }else{
                    // bei einem Zwischensaldo ist der Tag derselbe
                    tag.start.timestamp = new Date(SepaUtil.toDate(firstBal.getDt().getDt()).getTime());
                }
            }

            // Zweiter Balance Eintrag ist ein Schlusssaldo oder auch ein Zwischensaldo
            if(report.getBal().size()>1){
                CashBalance8 secondBal = report.getBal().get(1);
                String secondCode = secondBal.getTp().getCdOrPrtry().getCd();
                if(secondCode.equalsIgnoreCase("CLBD") || secondCode.equalsIgnoreCase("ITBD")) {
                    tag.end.value = new Value(this.checkDebit(secondBal.getAmt().getValue(),secondBal.getCdtDbtInd()));
                    tag.end.value.setCurr(secondBal.getAmt().getCcy());
                    tag.end.timestamp = SepaUtil.toDate(secondBal.getDt().getDt());
                }
            }


        }
        //
        ////////////////////////////////////////////////////////////////
        
        ////////////////////////////////////////////////////////////////
        // Das eigene Konto ermitteln
        CashAccount36 acc = report.getAcct();
        tag.my = new Konto();
        tag.my.iban = trim(acc.getId().getIBAN());
        tag.my.curr = trim(acc.getCcy());
        
        BranchAndFinancialInstitutionIdentification5 bank = acc.getSvcr();
        if (bank != null && bank.getFinInstnId() != null)
        tag.my.bic  = trim(bank.getFinInstnId().getBICFI());
        ////////////////////////////////////////////////////////////////
        
        return tag;
    }
    
    /**
     * Prueft, ob es sich um einen Soll-Betrag handelt und setzt in dem Fall ein negatives Vorzeichen vor den Wert.
     * @param d die zu pruefende Zahl.
     * @param code das Soll-/Haben-Kennzeichen.
     * @return der ggf korrigierte Betrag.
     */
    private BigDecimal checkDebit(BigDecimal d, CreditDebitCode code)
    {
        if (d == null || code == null || code == CreditDebitCode.CRDT)
            return d;
        
        return BigDecimal.ZERO.subtract(d);
    }
}



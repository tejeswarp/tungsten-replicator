/**
 * Tungsten: An Application Server for uni/cluster.
 * Copyright (C) 2014 Continuent Inc.
 * Contact: tungsten@continuent.org
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of version 2 of the GNU General Public License as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA
 *
 * Initial developer(s): Robert Hodges
 * Contributor(s): 
 */

package com.continuent.tungsten.common.csv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Reader;
import java.io.Writer;

import org.apache.commons.lang.StringEscapeUtils;

/**
 * Holds specification of properties for CSV input and output from which it
 * generates CSVWriter and CSVReader instances.
 */
public class CsvSpecification
{
    // Properties.
    private String     fieldSeparator          = ",";
    private String     recordSeparator         = "\n";
    private boolean    collapseFieldSeparators = false;
    private boolean    useHeaders              = false;
    private boolean    useQuotes               = false;
    private String     quote                   = "\"";
    private String     escape                  = "\\";
    private String     escapedChars            = "";
    private String     suppressedChars         = "";
    private NullPolicy nullPolicy              = NullPolicy.skip;
    private String     nullValue               = null;
    private boolean    nullAutofill            = false;

    /**
     * Sets the field separator character.
     */
    public void setFieldSeparator(String fieldSeparator)
    {
        this.fieldSeparator = StringEscapeUtils.unescapeJava(fieldSeparator);
    }

    /**
     * Returns field separator character.
     */
    public String getFieldSeparator()
    {
        return this.fieldSeparator;
    }

    /**
     * Returns true if successive input separators should be treated as a single
     * separator.
     */
    public boolean isCollapseFieldSeparators()
    {
        return collapseFieldSeparators;
    }

    /**
     * If set to true treat successive input separators as a single separator.
     */
    public void setCollapseFieldSeparators(boolean collapseFieldSeparators)
    {
        this.collapseFieldSeparators = collapseFieldSeparators;
    }

    /**
     * Sets the record separator character.
     */
    public void setRecordSeparator(String recordSeparator)
    {
        this.recordSeparator = StringEscapeUtils.unescapeJava(recordSeparator);
    }

    /**
     * Returns record separator character.
     */
    public String getRecordSeparator()
    {
        return this.recordSeparator;
    }

    /**
     * Returns true if CSV contains column headers in first row.
     */
    public boolean isUseHeaders()
    {
        return useHeaders;
    }

    /**
     * If set to true first row must contain column headers.
     */
    public void setUseHeaders(boolean useHeaders)
    {
        this.useHeaders = useHeaders;
    }

    /** Returns true if values will be enclosed by a quote character. */
    public synchronized boolean isUseQuotes()
    {
        return useQuotes;
    }

    /** Set to true to enable quoting. */
    public synchronized void setUseQuotes(boolean quoted)
    {
        this.useQuotes = quoted;
    }

    /** Returns the policy for handling null values. */
    public synchronized NullPolicy getNullPolicy()
    {
        return nullPolicy;
    }

    /** Sets the policy for handling null values. */
    public synchronized void setNullPolicy(NullPolicy nullPolicy)
    {
        this.nullPolicy = nullPolicy;
    }

    /** Gets the null value identifier string. */
    public synchronized String getNullValue()
    {
        return nullValue;
    }

    /**
     * Sets the null value identifier string. This applies only when null policy
     * is NullPolicy.nullValue.
     */
    public synchronized void setNullValue(String nullValue)
    {
        this.nullValue = nullValue;
    }

    /** Returns true to fill nulls automatically. */
    public synchronized boolean isNullAutofill()
    {
        return nullAutofill;
    }

    /**
     * Sets the null autofill policy for columns that have no value (partial
     * rows). If true, unwritten columns are filled with the prevailing null
     * value. If false, partial rows prompt an exception.
     */
    public synchronized void setNullAutofill(boolean nullAutofill)
    {
        this.nullAutofill = nullAutofill;
    }

    /** Returns the quote character. */
    public synchronized String getQuote()
    {
        return this.quote;
    }

    /** Sets the quote character. */
    public synchronized void setQuote(String quoteChar)
    {
        this.quote = quoteChar;
    }

    /**
     * Sets character used to escape quotes and other escaped characters.
     * 
     * @see #setQuote(char)
     */
    public synchronized void setEscape(String quoteEscapeChar)
    {
        this.escape = StringEscapeUtils.unescapeJava(quoteEscapeChar);
    }

    /** Returns the escape character. */
    public synchronized String getEscape()
    {
        return escape;
    }

    /**
     * Returns a string of characters that must be preceded by escape character.
     */
    public synchronized String getEscapedChars()
    {
        return escapedChars;
    }

    /**
     * Defines zero or more characters that must be preceded by escape
     * character.
     */
    public synchronized void setEscapedChars(String escapedChars)
    {
        if (escapedChars == null)
            this.escapedChars = "";
        else
            this.escapedChars = escapedChars;
    }

    /**
     * Returns a string of characters that are suppressed in CSV output.
     */
    public synchronized String getSuppressedChars()
    {
        return suppressedChars;
    }

    /**
     * Sets characters to be suppressed in CSV output.
     */
    public synchronized void setSuppressedChars(String suppressedChars)
    {
        if (suppressedChars == null)
            this.suppressedChars = "";
        else
            this.suppressedChars = suppressedChars;
    }

    /**
     * Instantiate a new CsvWriter with output to provided writer.
     */
    public CsvWriter createCsvWriter(Writer writer)
    {
        return createCsvWriter(new BufferedWriter(writer));
    }

    /**
     * Instantiate a new CsvWriter with output to provided buffered writer. This
     * call allows clients to set buffering parameters themselves.
     */
    public CsvWriter createCsvWriter(BufferedWriter writer)
    {
        CsvWriter csvWriter = new CsvWriter(writer);
        csvWriter.setEscapeChar(escape);
        csvWriter.setEscapedChars(escapedChars);
        csvWriter.setNullAutofill(nullAutofill);
        csvWriter.setNullPolicy(nullPolicy);
        csvWriter.setNullValue(nullValue);
        csvWriter.setQuoteChar(quote);
        csvWriter.setQuoted(useQuotes);
        csvWriter.setFieldSeparator(fieldSeparator);
        csvWriter.setRecordSeparator(recordSeparator);
        csvWriter.setSuppressedChars(suppressedChars);
        csvWriter.setWriteHeaders(useHeaders);
        return csvWriter;
    }

    /**
     * Instantiate a new CsvReader with input from provided reader.
     */
    public CsvReader createCsvReader(Reader reader)
    {
        return createCsvReader(new BufferedReader(reader));
    }

    /**
     * Instantiate a new CsvWriter with input from provided buffered reader.
     * This call allows clients to set buffering parameters themselves.
     */
    public CsvReader createCsvReader(BufferedReader reader)
    {
        CsvReader csvReader = new CsvReader(reader);
        csvReader.setFieldSeparator(fieldSeparator);
        csvReader.setRecordSeparator(recordSeparator);
        csvReader.setUseHeaders(useHeaders);
        return csvReader;
    }
}
package pignlproc.helpers;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;
import org.apache.pig.EvalFunc;
import org.apache.pig.FuncSpec;
import org.apache.pig.data.*;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.dbpedia.spotlight.db.model.StringTokenizer;
import org.dbpedia.spotlight.db.model.Stemmer;
import org.dbpedia.spotlight.db.tokenize.LanguageIndependentStringTokenizer;
import org.dbpedia.spotlight.db.tokenize.OpenNLPStringTokenizer;

import java.io.*;
import java.util.*;


/**
 * This is a modification of the NGramGenerator class. Additionally, a
 * distributed cache file is kept that contains all allowed surface forms.
 * Only ngrams matching a surface form are produced.
 */

public class RestrictedNGramGenerator extends EvalFunc<DataBag> {

    private int ngramSizeLimit;

    private StringTokenizer tokenizer;

    private final BagFactory bagFactory = DefaultBagFactory.getInstance();
    private final TupleFactory tupleFactory = TupleFactory.getInstance();

    private Set<String> surfaceFormLookup = new HashSet<String>();
    private String surfaceFormListFile;

    private String language;

    public RestrictedNGramGenerator(int ngramSizeLimit, String surfaceFormListFile, String locale) {
        this.ngramSizeLimit = ngramSizeLimit;
        this.surfaceFormListFile = surfaceFormListFile;
        String[] localeA = locale.split("_");

        this.language = localeA[0];

        if (new File("./tokenizer_model").length() > 10) {
            try {
                this.tokenizer = new OpenNLPStringTokenizer(new TokenizerME(new TokenizerModel(new FileInputStream(new File("./tokenizer_model")))), new Stemmer());
            } catch (IOException ignored) {}
        }

        if (this.tokenizer == null)
            this.tokenizer = new LanguageIndependentStringTokenizer(new Locale(localeA[0], localeA[1]), new Stemmer());

    }

    // Pig versions < 0.9 seem to only pass strings in constructor
    public RestrictedNGramGenerator(String ngramSizeLimit, String surfaceFormListFile, String locale) {
        this(Integer.valueOf(ngramSizeLimit), surfaceFormListFile, locale);
    }

    public List<String> getCacheFiles() {
        List<String> list = new ArrayList<String>(1);

        if (!this.surfaceFormListFile.equals(""))
            list.add(this.surfaceFormListFile + "#sfs");

        list.add(this.language + ".tokenizer_model" + "#tokenizer");
        return list;
    }

    @Override
    public DataBag exec(Tuple input) throws IOException {

        if (surfaceFormLookup.size() == 0 && !this.surfaceFormListFile.equals("")) {
            File folder = new File("./sfs");
            for (final File fileEntry: folder.listFiles()) {
                if (fileEntry.getName().startsWith("part-")) {
                    FileReader fr = new FileReader(fileEntry);
                    BufferedReader d = new BufferedReader(fr);
                    String strLine;
                    while ((strLine = d.readLine()) != null)   {
                        surfaceFormLookup.add(strLine.trim());
                    }
                    fr.close();
                }
            }
        }

        String text = (String)input.get(0);
        Span[] spans = tokenizer.tokenizePos(text);
        DataBag output = bagFactory.newDefaultBag();
        fillOutputWithNgrams(spans, text, output, this.ngramSizeLimit);
        return output;

    }

    /**
     * This is a simple utility function that make word-level ngrams from a set of words.
     * @param spans spans of tokens in the text
     * @param text the original text
     * @param output result bag of ngram strings
     * @param size number of words in an ngram (in this recursive call)
     */
    private void fillOutputWithNgrams(Span[] spans, String text, DataBag output, int size) {
        int stop = spans.length - size + 1;
        for (int startIdx = 0; startIdx < stop; startIdx++) {
            int endIdx = startIdx + size - 1;
            String ngram = text.substring(spans[startIdx].getStart(), spans[endIdx].getEnd());

            if (this.surfaceFormListFile.equals("") || surfaceFormLookup.contains(ngram)) {
                Tuple tuple = tupleFactory.newTuple(ngram);
                output.add(tuple);
            }
        }
        if (size > 1) {
            fillOutputWithNgrams(spans, text, output, size - 1);
        }
    }

    /**
     * This method gives a name to the column.
     * @param input - schema of the input data
     * @return schema of the input data
     */
    @Override
    public Schema outputSchema(Schema input) {
        Schema bagSchema = new Schema();
        bagSchema.add(new Schema.FieldSchema("ngram", DataType.CHARARRAY));
        try {
            return new Schema(new Schema.FieldSchema(getSchemaName(this.getClass().getName().toLowerCase(), input),
                    bagSchema, DataType.BAG));
        } catch (FrontendException e) {
            return null;
        }
    }

    /* (non-Javadoc)
     * @see org.apache.pig.EvalFunc#getArgToFuncMapping()
     * This is needed to make sure that both bytearrays and chararrays can be passed as arguments
     */
    @Override
    public List<FuncSpec> getArgToFuncMapping() throws FrontendException {
        List<FuncSpec> funcList = new ArrayList<FuncSpec>(1);
        funcList.add(new FuncSpec(this.getClass().getName(), new Schema(new Schema.FieldSchema(null, DataType.CHARARRAY))));
        return funcList;
    }

}

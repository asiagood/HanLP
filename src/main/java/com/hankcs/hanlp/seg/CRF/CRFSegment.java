/*
 * <summary></summary>
 * <author>He Han</author>
 * <email>hankcs.cn@gmail.com</email>
 * <create-date>2014/12/10 13:44</create-date>
 *
 * <copyright file="CRFSegment.java" company="上海林原信息科技有限公司">
 * Copyright (c) 2003-2014, 上海林原信息科技有限公司. All Right Reserved, http://www.linrunsoft.com/
 * This source is subject to the LinrunSpace License. Please contact 上海林原信息科技有限公司 to get more information.
 * </copyright>
 */
package com.hankcs.hanlp.seg.CRF;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.algoritm.Viterbi;
import com.hankcs.hanlp.collection.trie.bintrie.BinTrie;
import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.dictionary.CoreDictionary;
import com.hankcs.hanlp.dictionary.CoreDictionaryTransformMatrixDictionary;
import com.hankcs.hanlp.dictionary.other.CharTable;
import com.hankcs.hanlp.model.CRFSegmentModel;
import com.hankcs.hanlp.model.crf.CRFModel;
import com.hankcs.hanlp.model.crf.FeatureFunction;
import com.hankcs.hanlp.model.crf.Table;
import com.hankcs.hanlp.seg.CharacterBasedGenerativeModelSegment;
import com.hankcs.hanlp.seg.Segment;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.seg.common.Vertex;
import com.hankcs.hanlp.utility.CharacterHelper;
import com.hankcs.hanlp.utility.GlobalObjectPool;

import java.util.*;

import static com.hankcs.hanlp.utility.Predefine.logger;


/**
 * 基于CRF的分词器
 *
 * @author hankcs
 */
public class CRFSegment extends CharacterBasedGenerativeModelSegment
{
    private CRFModel crfModel;

    public CRFSegment(CRFSegmentModel crfModel)
    {
        this.crfModel = crfModel;
    }

    public CRFSegment(String modelPath)
    {
        CRFModel crfModel = GlobalObjectPool.get(modelPath);
        if (crfModel != null)
        {
            this.crfModel = crfModel;
            return;
        }
        logger.info("CRF分词模型正在加载 " + modelPath);
        long start = System.currentTimeMillis();
        this.crfModel = CRFModel.loadTxt(modelPath, new CRFSegmentModel(new BinTrie<FeatureFunction>()));
        if (this.crfModel == null)
        {
            String error = "CRF分词模型加载 " + modelPath + " 失败，耗时 " + (System.currentTimeMillis() - start) + " ms";
            logger.severe(error);
            throw new IllegalArgumentException(error);
        }
        else
            logger.info("CRF分词模型加载 " + modelPath + " 成功，耗时 " + (System.currentTimeMillis() - start) + " ms");
        GlobalObjectPool.put(modelPath, this.crfModel);
    }

    public CRFSegment()
    {
        this(HanLP.Config.CRFSegmentModelPath);
    }

    @Override
    protected List<Term> segSentence(char[] sentence)
    {
        if (sentence.length == 0) return Collections.emptyList();
        char[] sentenceConverted = CharTable.convert(sentence);
        Table table = new Table();
        table.v = atomSegmentToTable(sentenceConverted);
        crfModel.tag(table);
        List<Term> termList = new LinkedList<Term>();
        if (HanLP.Config.DEBUG)
        {
            System.out.println("CRF标注结果");
            System.out.println(table);
        }
        int offset = 0;
        OUTER:
        for (int i = 0; i < table.v.length; offset += table.v[i][1].length(), ++i)
        {
            String[] line = table.v[i];
            switch (line[2].charAt(0))
            {
                case 'B':
                {
                    int begin = offset;
                    while (table.v[i][2].charAt(0) != 'E')
                    {
                        offset += table.v[i][1].length();
                        ++i;
                        if (i == table.v.length)
                        {
                            break;
                        }
                    }
                    if (i == table.v.length)
                    {
                        termList.add(new Term(new String(sentence, begin, offset - begin), null));
                        break OUTER;
                    }
                    else
                        termList.add(new Term(new String(sentence, begin, offset - begin + table.v[i][1].length()), null));
                }
                break;
                default:
                {
                    termList.add(new Term(new String(sentence, offset, table.v[i][1].length()), null));
                }
                break;
            }
        }

        if (config.speechTagging)
        {
            List<Vertex> vertexList = toVertexList(termList, true);
            Viterbi.compute(vertexList, CoreDictionaryTransformMatrixDictionary.transformMatrixDictionary);
            int i = 0;
            for (Term term : termList)
            {
                if (term.nature != null) term.nature = vertexList.get(i + 1).guessNature();
                ++i;
            }
        }

        if (config.useCustomDictionary)
        {
            List<Vertex> vertexList = toVertexList(termList, false);
            combineByCustomDictionary(vertexList);
            termList = toTermList(vertexList, config.offset);
        }

        return termList;
    }

    private static List<Vertex> toVertexList(List<Term> termList, boolean appendStart)
    {
        ArrayList<Vertex> vertexList = new ArrayList<Vertex>(termList.size() + 1);
        if (appendStart) vertexList.add(Vertex.B);
        for (Term term : termList)
        {
            CoreDictionary.Attribute attribute = CoreDictionary.get(term.word);
            if (attribute == null)
            {
                if (term.word.trim().length() == 0) attribute = new CoreDictionary.Attribute(Nature.x);
                else attribute = new CoreDictionary.Attribute(Nature.nz);
            }
            else term.nature = attribute.nature[0];
            Vertex vertex = new Vertex(term.word, attribute);
            vertexList.add(vertex);
        }

        return vertexList;
    }

    /**
     * 将一条路径转为最终结果
     *
     * @param vertexList
     * @param offsetEnabled 是否计算offset
     * @return
     */
    protected static List<Term> toTermList(List<Vertex> vertexList, boolean offsetEnabled)
    {
        assert vertexList != null;
        int length = vertexList.size();
        List<Term> resultList = new ArrayList<Term>(length);
        Iterator<Vertex> iterator = vertexList.iterator();
        if (offsetEnabled)
        {
            int offset = 0;
            for (int i = 0; i < length; ++i)
            {
                Vertex vertex = iterator.next();
                Term term = convert(vertex);
                term.offset = offset;
                offset += term.length();
                resultList.add(term);
            }
        }
        else
        {
            for (int i = 0; i < length; ++i)
            {
                Vertex vertex = iterator.next();
                Term term = convert(vertex);
                resultList.add(term);
            }
        }
        return resultList;
    }

    /**
     * 将节点转为term
     *
     * @param vertex
     * @return
     */
    private static Term convert(Vertex vertex)
    {
        return new Term(vertex.realWord, vertex.guessNature());
    }

    public static List<String> atomSegment(char[] sentence)
    {
        List<String> atomList = new ArrayList<String>(sentence.length);
        final int maxLen = sentence.length - 1;
        final StringBuilder sbAtom = new StringBuilder();
        out:
        for (int i = 0; i < sentence.length; i++)
        {
            if (sentence[i] >= '0' && sentence[i] <= '9')
            {
                sbAtom.append(sentence[i]);
                if (i == maxLen)
                {
                    atomList.add(sbAtom.toString());
                    sbAtom.setLength(0);
                    break;
                }
                char c = sentence[++i];
                while (c == '.' || c == '%' || (c >= '0' && c <= '9'))
                {
                    sbAtom.append(sentence[i]);
                    if (i == maxLen)
                    {
                        atomList.add(sbAtom.toString());
                        sbAtom.setLength(0);
                        break out;
                    }
                    c = sentence[++i];
                }
                atomList.add(sbAtom.toString());
                sbAtom.setLength(0);
                --i;
            }
            else if (CharacterHelper.isEnglishLetter(sentence[i]))
            {
                sbAtom.append(sentence[i]);
                if (i == maxLen)
                {
                    atomList.add(sbAtom.toString());
                    sbAtom.setLength(0);
                    break;
                }
                char c = sentence[++i];
                while (CharacterHelper.isEnglishLetter(c))
                {
                    sbAtom.append(sentence[i]);
                    if (i == maxLen)
                    {
                        atomList.add(sbAtom.toString());
                        sbAtom.setLength(0);
                        break out;
                    }
                    c = sentence[++i];
                }
                atomList.add(sbAtom.toString());
                sbAtom.setLength(0);
                --i;
            }
            else
            {
                atomList.add(String.valueOf(sentence[i]));
            }
        }

        return atomList;
    }

    public static String[][] atomSegmentToTable(char[] sentence)
    {
        String table[][] = new String[sentence.length][3];
        int size = 0;
        final int maxLen = sentence.length - 1;
        final StringBuilder sbAtom = new StringBuilder();
        out:
        for (int i = 0; i < sentence.length; i++)
        {
            if (sentence[i] >= '0' && sentence[i] <= '9')
            {
                sbAtom.append(sentence[i]);
                if (i == maxLen)
                {
                    table[size][0] = "M";
                    table[size][1] = sbAtom.toString();
                    ++size;
                    sbAtom.setLength(0);
                    break;
                }
                char c = sentence[++i];
                while (c == '.' || c == '%' || (c >= '0' && c <= '9'))
                {
                    sbAtom.append(sentence[i]);
                    if (i == maxLen)
                    {
                        table[size][0] = "M";
                        table[size][1] = sbAtom.toString();
                        ++size;
                        sbAtom.setLength(0);
                        break out;
                    }
                    c = sentence[++i];
                }
                table[size][0] = "M";
                table[size][1] = sbAtom.toString();
                ++size;
                sbAtom.setLength(0);
                --i;
            }
            else if (CharacterHelper.isEnglishLetter(sentence[i]) || sentence[i] == ' ')
            {
                sbAtom.append(sentence[i]);
                if (i == maxLen)
                {
                    table[size][0] = "W";
                    table[size][1] = sbAtom.toString();
                    ++size;
                    sbAtom.setLength(0);
                    break;
                }
                char c = sentence[++i];
                while (CharacterHelper.isEnglishLetter(c) || c == ' ')
                {
                    sbAtom.append(sentence[i]);
                    if (i == maxLen)
                    {
                        table[size][0] = "W";
                        table[size][1] = sbAtom.toString();
                        ++size;
                        sbAtom.setLength(0);
                        break out;
                    }
                    c = sentence[++i];
                }
                table[size][0] = "W";
                table[size][1] = sbAtom.toString();
                ++size;
                sbAtom.setLength(0);
                --i;
            }
            else
            {
                table[size][0] = table[size][1] = String.valueOf(sentence[i]);
                ++size;
            }
        }

        return resizeArray(table, size);
    }

    /**
     * 数组减肥，原子分词可能会导致表格比原来的短
     *
     * @param array
     * @param size
     * @return
     */
    private static String[][] resizeArray(String[][] array, int size)
    {
        String[][] nArray = new String[size][];
        System.arraycopy(array, 0, nArray, 0, size);
        return nArray;
    }

    @Override
    public Segment enableNumberQuantifierRecognize(boolean enable)
    {
        throw new UnsupportedOperationException("暂不支持");
//        enablePartOfSpeechTagging(enable);
//        return super.enableNumberQuantifierRecognize(enable);
    }
}

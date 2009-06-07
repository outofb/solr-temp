package org.apache.lucene.search;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/* See the description in BooleanScorer.java, comparing
 * BooleanScorer & BooleanScorer2 */

/** An alternative to BooleanScorer that also allows a minimum number
 * of optional scorers that should match.
 * <br>Implements skipTo(), and has no limitations on the numbers of added scorers.
 * <br>Uses ConjunctionScorer, DisjunctionScorer, ReqOptScorer and ReqExclScorer.
 */
class BooleanScorer2 extends Scorer {
  private ArrayList requiredScorers = new ArrayList();
  private ArrayList optionalScorers = new ArrayList();
  private ArrayList prohibitedScorers = new ArrayList();

  private class Coordinator {
    float[] coordFactors = null;
    int maxCoord = 0; // to be increased for each non prohibited scorer
    int nrMatchers; // to be increased by score() of match counting scorers.
    
    void init() { // use after all scorers have been added.
      coordFactors = new float[maxCoord + 1];
      Similarity sim = getSimilarity();
      for (int i = 0; i <= maxCoord; i++) {
        coordFactors[i] = sim.coord(i, maxCoord);
      }
    }
    
  }

  private final Coordinator coordinator;

  /** The scorer to which all scoring will be delegated,
   * except for computing and using the coordination factor.
   */
  private Scorer countingSumScorer = null;

  /** The number of optionalScorers that need to match (if there are any) */
  private final int minNrShouldMatch;
  
  /** Whether it is allowed to return documents out of order.
   *  This can accelerate the scoring of disjunction queries.  
   */  
  private boolean allowDocsOutOfOrder;

  private int doc = -1;

  /** Create a BooleanScorer2.
   * @param similarity The similarity to be used.
   * @param minNrShouldMatch The minimum number of optional added scorers
   *                         that should match during the search.
   *                         In case no required scorers are added,
   *                         at least one of the optional scorers will have to
   *                         match during the search.
   * @param allowDocsOutOfOrder Whether it is allowed to return documents out of order.
   *                            This can accelerate the scoring of disjunction queries.                         
   */
  public BooleanScorer2(Similarity similarity, int minNrShouldMatch, boolean allowDocsOutOfOrder) throws IOException {
    super(similarity);
    if (minNrShouldMatch < 0) {
      throw new IllegalArgumentException("Minimum number of optional scorers should not be negative");
    }
    coordinator = new Coordinator();
    this.minNrShouldMatch = minNrShouldMatch;
    this.allowDocsOutOfOrder = allowDocsOutOfOrder;
  }

  /** Create a BooleanScorer2.
   *  In no required scorers are added,
   *  at least one of the optional scorers will have to match during the search.
   * @param similarity The similarity to be used.
   * @param minNrShouldMatch The minimum number of optional added scorers
   *                         that should match during the search.
   *                         In case no required scorers are added,
   *                         at least one of the optional scorers will have to
   *                         match during the search.
   */
  public BooleanScorer2(Similarity similarity, int minNrShouldMatch) throws IOException {
    this(similarity, minNrShouldMatch, false);
  }
  
  /** Create a BooleanScorer2.
   *  In no required scorers are added,
   *  at least one of the optional scorers will have to match during the search.
   * @param similarity The similarity to be used.
   */
  public BooleanScorer2(Similarity similarity) throws IOException {
    this(similarity, 0, false);
  }

  public void add(final Scorer scorer, boolean required, boolean prohibited) throws IOException {
    if (!prohibited) {
      coordinator.maxCoord++;
    }

    if (required) {
      if (prohibited) {
        throw new IllegalArgumentException("scorer cannot be required and prohibited");
      }
      requiredScorers.add(scorer);
    } else if (prohibited) {
      prohibitedScorers.add(scorer);
    } else {
      optionalScorers.add(scorer);
    }
  }

  /** Initialize the match counting scorer that sums all the
   * scores. <p>
   * When "counting" is used in a name it means counting the number
   * of matching scorers.<br>
   * When "sum" is used in a name it means score value summing
   * over the matching scorers
   */
  private void initCountingSumScorer() throws IOException {
    coordinator.init();
    countingSumScorer = makeCountingSumScorer();
  }

  /** Count a scorer as a single match. */
  private class SingleMatchScorer extends Scorer {
    private Scorer scorer;
    private int lastScoredDoc = -1;
    // Save the score of lastScoredDoc, so that we don't compute it more than
    // once in score().
    private float lastDocScore = Float.NaN;

    SingleMatchScorer(Scorer scorer) {
      super(scorer.getSimilarity());
      this.scorer = scorer;
    }
    public float score() throws IOException {
      int doc = docID();
      if (doc >= lastScoredDoc) {
        if (doc > lastScoredDoc) {
          lastDocScore = scorer.score();
          lastScoredDoc = doc;
        }
        coordinator.nrMatchers++;
      }
      return lastDocScore;
    }
    /** @deprecated use {@link #docID()} instead. */
    public int doc() {
      return scorer.doc();
    }
    public int docID() {
      return scorer.docID();
    }
    /** @deprecated use {@link #nextDoc()} instead. */
    public boolean next() throws IOException {
      return scorer.nextDoc() != NO_MORE_DOCS;
    }
    public int nextDoc() throws IOException {
      return scorer.nextDoc();
    }
    /** @deprecated use {@link #advance(int)} instead. */
    public boolean skipTo(int docNr) throws IOException {
      return scorer.advance(docNr) != NO_MORE_DOCS;
    }
    public int advance(int target) throws IOException {
      return scorer.advance(target);
    }
    public Explanation explain(int docNr) throws IOException {
      return scorer.explain(docNr);
    }
  }

  private Scorer countingDisjunctionSumScorer(final List scorers,
      int minNrShouldMatch) throws IOException {
    // each scorer from the list counted as a single matcher
    return new DisjunctionSumScorer(scorers, minNrShouldMatch) {
      private int lastScoredDoc = -1;
      // Save the score of lastScoredDoc, so that we don't compute it more than
      // once in score().
      private float lastDocScore = Float.NaN;
      public float score() throws IOException {
        int doc = docID();
        if (doc >= lastScoredDoc) {
          if (doc > lastScoredDoc) {
            lastDocScore = super.score();
            lastScoredDoc = doc;
          }
          coordinator.nrMatchers += super.nrMatchers;
        }
        return lastDocScore;
      }
    };
  }

  private static final Similarity defaultSimilarity = Similarity.getDefault();

  private Scorer countingConjunctionSumScorer(List requiredScorers) throws IOException {
    // each scorer from the list counted as a single matcher
    final int requiredNrMatchers = requiredScorers.size();
    return new ConjunctionScorer(defaultSimilarity, requiredScorers) {
      private int lastScoredDoc = -1;
      // Save the score of lastScoredDoc, so that we don't compute it more than
      // once in score().
      private float lastDocScore = Float.NaN;
      public float score() throws IOException {
        int doc = docID();
        if (doc >= lastScoredDoc) {
          if (doc > lastScoredDoc) {
            lastDocScore = super.score();
            lastScoredDoc = doc;
          }
          coordinator.nrMatchers += requiredNrMatchers;
        }
        // All scorers match, so defaultSimilarity super.score() always has 1 as
        // the coordination factor.
        // Therefore the sum of the scores of the requiredScorers
        // is used as score.
        return lastDocScore;
      }
    };
  }

  private Scorer dualConjunctionSumScorer(Scorer req1, Scorer req2) throws IOException { // non counting.
    return new ConjunctionScorer(defaultSimilarity, new Scorer[]{req1, req2});
    // All scorers match, so defaultSimilarity always has 1 as
    // the coordination factor.
    // Therefore the sum of the scores of two scorers
    // is used as score.
  }

  /** Returns the scorer to be used for match counting and score summing.
   * Uses requiredScorers, optionalScorers and prohibitedScorers.
   */
  private Scorer makeCountingSumScorer() throws IOException { // each scorer counted as a single matcher
    return (requiredScorers.size() == 0)
          ? makeCountingSumScorerNoReq()
          : makeCountingSumScorerSomeReq();
  }

  private Scorer makeCountingSumScorerNoReq() throws IOException { // No required scorers
    if (optionalScorers.size() == 0) {
      return new NonMatchingScorer(); // no clauses or only prohibited clauses
    } else { // No required scorers. At least one optional scorer.
      // minNrShouldMatch optional scorers are required, but at least 1
      int nrOptRequired = (minNrShouldMatch < 1) ? 1 : minNrShouldMatch;
      if (optionalScorers.size() < nrOptRequired) { 
        return new NonMatchingScorer(); // fewer optional clauses than minimum (at least 1) that should match
      } else { // optionalScorers.size() >= nrOptRequired, no required scorers
        Scorer requiredCountingSumScorer =
              (optionalScorers.size() > nrOptRequired)
              ? countingDisjunctionSumScorer(optionalScorers, nrOptRequired)
              : // optionalScorers.size() == nrOptRequired (all optional scorers are required), no required scorers
              (optionalScorers.size() == 1)
              ? new SingleMatchScorer((Scorer) optionalScorers.get(0))
              : countingConjunctionSumScorer(optionalScorers);
        return addProhibitedScorers(requiredCountingSumScorer);
      }
    }
  }

  private Scorer makeCountingSumScorerSomeReq() throws IOException { // At least one required scorer.
    if (optionalScorers.size() < minNrShouldMatch) {
      return new NonMatchingScorer(); // fewer optional clauses than minimum that should match
    } else if (optionalScorers.size() == minNrShouldMatch) { // all optional scorers also required.
      ArrayList allReq = new ArrayList(requiredScorers);
      allReq.addAll(optionalScorers);
      return addProhibitedScorers(countingConjunctionSumScorer(allReq));
    } else { // optionalScorers.size() > minNrShouldMatch, and at least one required scorer
      Scorer requiredCountingSumScorer =
            (requiredScorers.size() == 1)
            ? new SingleMatchScorer((Scorer) requiredScorers.get(0))
            : countingConjunctionSumScorer(requiredScorers);
      if (minNrShouldMatch > 0) { // use a required disjunction scorer over the optional scorers
        return addProhibitedScorers( 
                      dualConjunctionSumScorer( // non counting
                              requiredCountingSumScorer,
                              countingDisjunctionSumScorer(
                                      optionalScorers,
                                      minNrShouldMatch)));
      } else { // minNrShouldMatch == 0
        return new ReqOptSumScorer(
                      addProhibitedScorers(requiredCountingSumScorer),
                      ((optionalScorers.size() == 1)
                        ? new SingleMatchScorer((Scorer) optionalScorers.get(0))
                        : countingDisjunctionSumScorer(optionalScorers, 1))); // require 1 in combined, optional scorer.
      }
    }
  }
  
  /** Returns the scorer to be used for match counting and score summing.
   * Uses the given required scorer and the prohibitedScorers.
   * @param requiredCountingSumScorer A required scorer already built.
   */
  private Scorer addProhibitedScorers(Scorer requiredCountingSumScorer) throws IOException
  {
    return (prohibitedScorers.size() == 0)
          ? requiredCountingSumScorer // no prohibited
          : new ReqExclScorer(requiredCountingSumScorer,
                              ((prohibitedScorers.size() == 1)
                                ? (Scorer) prohibitedScorers.get(0)
                                : new DisjunctionSumScorer(prohibitedScorers)));
  }

  /** Scores and collects all matching documents.
   * @param hc The collector to which all matching documents are passed through
   * {@link HitCollector#collect(int, float)}.
   * <br>When this method is used the {@link #explain(int)} method should not be used.
   * @deprecated use {@link #score(Collector)} instead.
   */
  public void score(HitCollector hc) throws IOException {
    score(new HitCollectorWrapper(hc));
  }

  /** Scores and collects all matching documents.
   * @param collector The collector to which all matching documents are passed through.
   * <br>When this method is used the {@link #explain(int)} method should not be used.
   */
  public void score(Collector collector) throws IOException {
    if (allowDocsOutOfOrder && requiredScorers.size() == 0
            && prohibitedScorers.size() < 32) {
      new BooleanScorer(getSimilarity(), minNrShouldMatch, optionalScorers,
          prohibitedScorers).score(collector);
    } else {
      if (countingSumScorer == null) {
        initCountingSumScorer();
      }
      collector.setScorer(this);
      int doc;
      while ((doc = countingSumScorer.nextDoc()) != NO_MORE_DOCS) {
        collector.collect(doc);
      }
    }
  }

  /** Expert: Collects matching documents in a range.
   * <br>Note that {@link #next()} must be called once before this method is
   * called for the first time.
   * @param hc The collector to which all matching documents are passed through
   * {@link HitCollector#collect(int, float)}.
   * @param max Do not score documents past this.
   * @return true if more matching documents may remain.
   * @deprecated use {@link #score(Collector, int)} instead.
   */
  protected boolean score(HitCollector hc, int max) throws IOException {
    return score(new HitCollectorWrapper(hc), max, docID());
  }
  
  protected boolean score(Collector collector, int max, int firstDocID) throws IOException {
    // null pointer exception when next() was not called before:
    int docNr = firstDocID;
    collector.setScorer(this);
    while (docNr < max) {
      collector.collect(docNr);
      docNr = countingSumScorer.nextDoc();
    }
    return docNr != NO_MORE_DOCS;
  }

  /** @deprecated use {@link #docID()} instead. */
  public int doc() { return countingSumScorer.doc(); }

  public int docID() {
    return doc;
  }
  
  /** @deprecated use {@link #nextDoc()} instead. */
  public boolean next() throws IOException {
    return nextDoc() != NO_MORE_DOCS;
  }

  public int nextDoc() throws IOException {
    if (countingSumScorer == null) {
      initCountingSumScorer();
    }
    return doc = countingSumScorer.nextDoc();
  }
  
  public float score() throws IOException {
    coordinator.nrMatchers = 0;
    float sum = countingSumScorer.score();
    return sum * coordinator.coordFactors[coordinator.nrMatchers];
  }

  /** @deprecated use {@link #advance(int)} instead. */
  public boolean skipTo(int target) throws IOException {
    return advance(target) != NO_MORE_DOCS;
  }

  public int advance(int target) throws IOException {
    if (countingSumScorer == null) {
      initCountingSumScorer();
    }
    return doc = countingSumScorer.advance(target);
  }
  
  /** Throws an UnsupportedOperationException.
   * TODO: Implement an explanation of the coordination factor.
   * @param doc The document number for the explanation.
   * @throws UnsupportedOperationException
   */
  public Explanation explain(int doc) {
    throw new UnsupportedOperationException();
 /* How to explain the coordination factor?
    initCountingSumScorer();
    return countingSumScorer.explain(doc); // misses coord factor. 
  */
  }
}



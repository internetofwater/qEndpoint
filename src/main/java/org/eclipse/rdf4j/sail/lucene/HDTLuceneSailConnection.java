package org.eclipse.rdf4j.sail.lucene;

import com.google.common.collect.Sets;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.query.algebra.BindingSetAssignment;
import org.eclipse.rdf4j.query.algebra.MultiProjection;
import org.eclipse.rdf4j.query.algebra.Projection;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryContext;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizerPipeline;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.AbstractFederatedServiceResolver;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedServiceResolver;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.*;
import org.eclipse.rdf4j.query.algebra.evaluation.iterator.QueryContextIteration;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.federation.SPARQLServiceResolver;
import org.eclipse.rdf4j.sail.NotifyingSailConnection;
import org.eclipse.rdf4j.sail.SailConnectionListener;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.evaluation.TupleFunctionEvaluationMode;
import org.eclipse.rdf4j.sail.helpers.NotifyingSailConnectionWrapper;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
import org.rdfhdt.hdt.rdf4j.CombinedEvaluationStatistics;
import org.rdfhdt.hdt.rdf4j.HDTEvaluationStatisticsV2;
import org.rdfhdt.hdt.rdf4j.VariableToIdSubstitution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class HDTLuceneSailConnection extends NotifyingSailConnectionWrapper {

  @SuppressWarnings("unchecked")
  private static final Set<Class<? extends QueryModelNode>> PROJECTION_TYPES =
      Sets.newHashSet(Projection.class, MultiProjection.class);

  private final Logger logger = LoggerFactory.getLogger(LuceneSailConnection.class);

  private final SearchIndex luceneIndex;

  @SuppressWarnings("unused")
  private final AbstractFederatedServiceResolver tupleFunctionServiceResolver;

  private final HDTLuceneSail  sail;

  /** the buffer that collects operations */
  private final LuceneSailBuffer buffer = new LuceneSailBuffer();

  /**
   * The listener that listens to the underlying connection. It is disabled during clearContext
   * operations.
   */
  protected final SailConnectionListener connectionListener =
      new SailConnectionListener() {

        @Override
        public void statementAdded(Statement statement) {
          // we only consider statements that contain literals
          if (statement.getObject() instanceof Literal) {
            statement = sail.mapStatement(statement);
            if (statement == null) return;
            // we further only index statements where the Literal's datatype is
            // accepted
            Literal literal = (Literal) statement.getObject();
            if (luceneIndex.accept(literal)) buffer.add(statement);
          }
        }

        @Override
        public void statementRemoved(Statement statement) {
          // we only consider statements that contain literals
          if (statement.getObject() instanceof Literal) {
            statement = sail.mapStatement(statement);
            if (statement == null) return;
            // we further only indexed statements where the Literal's datatype
            // is accepted
            Literal literal = (Literal) statement.getObject();
            if (luceneIndex.accept(literal)) buffer.remove(statement);
          }
        }
      };

  /** To remember if the iterator was already closed and only free resources once */
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private RepositoryConnection hybridStoreConnection;

  public HDTLuceneSailConnection(
          NotifyingSailConnection wrappedConnection, SearchIndex luceneIndex, HDTLuceneSail sail) {
    super(wrappedConnection);
    this.luceneIndex = luceneIndex;
    this.sail = sail;
    if (sail.getEvaluationMode() == TupleFunctionEvaluationMode.SERVICE) {
      FederatedServiceResolver resolver = sail.getFederatedServiceResolver();
      if (!(resolver instanceof AbstractFederatedServiceResolver)) {
        throw new IllegalArgumentException(
            "SERVICE EvaluationMode requires a FederatedServiceResolver that is an instance of "
                + AbstractFederatedServiceResolver.class.getName());
      }
      this.tupleFunctionServiceResolver = (AbstractFederatedServiceResolver) resolver;
    } else {
      this.tupleFunctionServiceResolver = null;
    }

    /*
     * Using SailConnectionListener, see <a href="#whySailConnectionListener">above</a>
     */

    wrappedConnection.addConnectionListener(connectionListener);
  }
  public HDTLuceneSailConnection(
          NotifyingSailConnection wrappedConnection, SearchIndex luceneIndex, HDTLuceneSail sail, RepositoryConnection hybridStoreConnection) {
    super(wrappedConnection);
    this.luceneIndex = luceneIndex;
    this.sail = sail;
    this.hybridStoreConnection = hybridStoreConnection;
    if (sail.getEvaluationMode() == TupleFunctionEvaluationMode.SERVICE) {
      FederatedServiceResolver resolver = sail.getFederatedServiceResolver();
      if (!(resolver instanceof AbstractFederatedServiceResolver)) {
        throw new IllegalArgumentException(
                "SERVICE EvaluationMode requires a FederatedServiceResolver that is an instance of "
                        + AbstractFederatedServiceResolver.class.getName());
      }
      this.tupleFunctionServiceResolver = (AbstractFederatedServiceResolver) resolver;
    } else {
      this.tupleFunctionServiceResolver = null;
    }

    /*
     * Using SailConnectionListener, see <a href="#whySailConnectionListener">above</a>
     */

    wrappedConnection.addConnectionListener(connectionListener);
  }

  @Override
  public synchronized void addStatement(Resource subj, IRI pred, Value obj, Resource... contexts)
      throws SailException {
    super.addStatement(subj, pred, obj, contexts);
  }

  @Override
  public void close() throws SailException {
    if (closed.compareAndSet(false, true)) {
      super.close();
    }
  }

  // //////////////////////////////// Methods related to indexing

  @Override
  public synchronized void clear(Resource... contexts) throws SailException {
    // remove the connection listener, this is safe as the changing methods
    // are synchronized
    // during the clear(), no other operation can be invoked
    getWrappedConnection().removeConnectionListener(connectionListener);
    try {
      super.clear(contexts);
      buffer.clear(contexts);
    } finally {
      getWrappedConnection().addConnectionListener(connectionListener);
    }
  }

  private int getCurrentCount(){
    String queryCount = "select (count(*) as ?c) where { ?s ?p ?o}";

    TupleQuery tupleQuery = this.sail.getHybridStore().getRepoConnection().prepareTupleQuery(queryCount);
    try (TupleQueryResult result = tupleQuery.evaluate()) {
      while (result.hasNext()) {
        BindingSet bindingSet = result.next();
        Value valueOfC = bindingSet.getValue("c");
        return Integer.parseInt(valueOfC.stringValue());
      }
    }
    return 0;
  }
  @Override
  public void begin() throws SailException {
    System.out.println("Making an update...");
    System.out.println(getCurrentCount());
    if (getCurrentCount() > 1000)
      this.sail.getHybridStore().switchStore = !this.sail.getHybridStore().switchStore;
    super.begin();

    buffer.reset();
    try {
      luceneIndex.begin();
    } catch (IOException e) {
      throw new SailException(e);
    }
  }

  @Override
  public void commit() throws SailException {
    super.commit();
    logger.debug("Committing Lucene transaction with {} operations.", buffer.operations().size());
    try {
      // preprocess buffer
      buffer.optimize();

      // run operations and remove them from buffer
      for (Iterator<LuceneSailBuffer.Operation> i = buffer.operations().iterator(); i.hasNext(); ) {
        LuceneSailBuffer.Operation op = i.next();
        if (op instanceof LuceneSailBuffer.AddRemoveOperation) {
          LuceneSailBuffer.AddRemoveOperation addremove = (LuceneSailBuffer.AddRemoveOperation) op;
          // add/remove in one call
          addRemoveStatements(addremove.getAdded(), addremove.getRemoved());
        } else if (op instanceof LuceneSailBuffer.ClearContextOperation) {
          // clear context
          clearContexts(((LuceneSailBuffer.ClearContextOperation) op).getContexts());
        } else if (op instanceof LuceneSailBuffer.ClearOperation) {
          logger.debug("clearing index...");
          luceneIndex.clear();
        } else {
          throw new SailException(
              "Cannot interpret operation " + op + " of type " + op.getClass().getName());
        }
        i.remove();
      }
    } catch (Exception e) {
      logger.error(
          "Committing operations in lucenesail, encountered exception "
              + e
              + ". Only some operations were stored, "
              + buffer.operations().size()
              + " operations are discarded. Lucene Index is now corrupt.",
          e);
      throw new SailException(e);
    } finally {
      buffer.reset();
    }
  }

  private void addRemoveStatements(Set<Statement> toAdd, Set<Statement> toRemove)
      throws IOException {
    logger.debug("indexing {}/removing {} statements...", toAdd.size(), toRemove.size());
    luceneIndex.begin();
    try {
      luceneIndex.addRemoveStatements(toAdd, toRemove);
      luceneIndex.commit();
    } catch (IOException e) {
      logger.error("Rolling back", e);
      luceneIndex.rollback();
      throw e;
    }
  }

  private void clearContexts(Resource... contexts) throws IOException {
    logger.debug("clearing contexts...");
    luceneIndex.begin();
    try {
      luceneIndex.clearContexts(contexts);
      luceneIndex.commit();
    } catch (IOException e) {
      logger.error("Rolling back", e);
      luceneIndex.rollback();
      throw e;
    }
  }
  // //////////////////////////////// Methods related to querying

  @Override
  public synchronized CloseableIteration<? extends BindingSet, QueryEvaluationException> evaluate(
      TupleExpr tupleExpr, Dataset dataset, BindingSet bindings, boolean includeInferred)
      throws SailException {
    QueryContext qctx = new QueryContext();
    SearchIndexQueryContextInitializer.init(qctx, luceneIndex);

    final CloseableIteration<? extends BindingSet, QueryEvaluationException> iter;
    qctx.begin();
    try {
      iter = evaluateInternal(tupleExpr, dataset, bindings, includeInferred);
    } finally {
      qctx.end();
    }

    // NB: Iteration methods may do on-demand evaluation hence need to wrap
    // these too
    return new QueryContextIteration(iter, qctx);
  }

  private CloseableIteration<? extends BindingSet, QueryEvaluationException> evaluateInternal(
      TupleExpr tupleExpr, Dataset dataset, BindingSet bindings, boolean includeInferred)
      throws SailException {
    // Don't modify the original tuple expression
    tupleExpr = tupleExpr.clone();

    if (!(tupleExpr instanceof QueryRoot)) {
      // Add a dummy root node to the tuple expressions to allow the
      // optimizers to modify the actual root node
      tupleExpr = new QueryRoot(tupleExpr);
    }

    // Inline any externally set bindings, lucene statement patterns can also
    // use externally bound variables
    new BindingAssigner().optimize(tupleExpr, dataset, bindings);

    List<SearchQueryEvaluator> queries = new ArrayList<>();

    for (SearchQueryInterpreter interpreter : sail.getSearchQueryInterpreters()) {
      interpreter.process(tupleExpr, bindings, queries);
    }

    // constant optimizer - evaluate lucene queries
    if (!queries.isEmpty()) {
      System.out.println("the query is using lucene...");
      evaluateLuceneQueries(queries);
    }

    if (sail.getEvaluationMode() == TupleFunctionEvaluationMode.TRIPLE_SOURCE) {

      HDTEvaluationStatisticsV2 hdtEvaluationStatistics =
          new HDTEvaluationStatisticsV2(sail.getHybridStore().getHdt());
      CombinedEvaluationStatistics combinedEvaluationStatistics  = new CombinedEvaluationStatistics(
              hdtEvaluationStatistics,
              this.sail.getHybridStore().getNativeStoreA().getSailStore().getEvaluationStatistics()
      );
      EvaluationStrategy strategy =
          new ExtendedEvaluationStrategy(
              sail.getHybridStore().getTripleSource(),
              dataset,
              new SPARQLServiceResolver(),
              0L,
              combinedEvaluationStatistics);
      new VariableToIdSubstitution(sail.getHybridStore().getHdt())
          .optimize(tupleExpr, dataset, bindings);
      new BindingAssigner().optimize(tupleExpr, dataset, bindings);
      new ConstantOptimizer(strategy).optimize(tupleExpr, dataset, bindings);
      new CompareOptimizer().optimize(tupleExpr, dataset, bindings);
      new ConjunctiveConstraintSplitter().optimize(tupleExpr, dataset, bindings);
      new DisjunctiveConstraintOptimizer().optimize(tupleExpr, dataset, bindings);
      new SameTermFilterOptimizer().optimize(tupleExpr, dataset, bindings);
      new QueryModelNormalizer().optimize(tupleExpr, dataset, bindings);
      new QueryJoinOptimizer(combinedEvaluationStatistics).optimize(tupleExpr, dataset, bindings);
      new IterativeEvaluationOptimizer().optimize(tupleExpr, dataset, bindings);
      // new FilterOptimizer().optimize(tupleExpr, dataset, bindings);
      new OrderLimitOptimizer().optimize(tupleExpr, dataset, bindings);

      logger.info("Optimized query model:\n{}", tupleExpr);

      // System.out.println("Optimized query model:\n{}"+ tupleExpr);
      try {
        return strategy.evaluate(tupleExpr, bindings);
      } catch (QueryEvaluationException e) {
        throw new SailException(e);
      }
    } else {
      return super.evaluate(tupleExpr, dataset, bindings, includeInferred);
    }
  }

  /**
   * Evaluate the given Lucene queries, generate bindings from the query result, add the bindings to
   * the query tree, and remove the Lucene queries from the given query tree.
   *
   * @param queries
   * @throws SailException
   */
  private void evaluateLuceneQueries(Collection<SearchQueryEvaluator> queries)
      throws SailException {
    // TODO: optimize lucene queries here
    // - if they refer to the same subject, merge them into one lucene query
    // - multiple different property constraints can be put into the lucene
    // query string (escape colons here)

    if (closed.get()) {
      throw new SailException("Sail has been closed already");
    }

    // evaluate queries, generate binding sets, and remove queries
    for (SearchQueryEvaluator query : queries) {
      // evaluate the Lucene query and generate bindings
      final Collection<BindingSet> bindingSets = luceneIndex.evaluate(query);

        System.out.println("Binding sets size:"+bindingSets.size());
      final BindingSetAssignment bsa = new BindingSetAssignment();

      // found something?
      if (bindingSets != null && !bindingSets.isEmpty()) {
        bsa.setBindingSets(bindingSets);
        if (bindingSets instanceof MyBindingSetCollection) {
          bsa.setBindingNames(((MyBindingSetCollection) bindingSets).getBindingNames());
        }
      }

      query.replaceQueryPatternsWithResults(bsa);
    }
  }

  @Override
  public synchronized void removeStatements(
      Resource subj, IRI pred, Value obj, Resource... contexts) throws SailException {
    super.removeStatements(subj, pred, obj, contexts);
  }

  @Override
  public void rollback() throws SailException {
    super.rollback();
    buffer.reset();
    try {
      luceneIndex.rollback();
    } catch (IOException e) {
      throw new SailException(e);
    }
  }
}

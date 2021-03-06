package SearchStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import aima.core.search.adversarial.AdversarialSearch;
import aima.core.search.adversarial.Game;
import aima.core.search.adversarial.IterativeDeepeningAlphaBetaSearch;
import aima.core.search.adversarial.Metrics;
import it.unibo.ai.didattica.competition.tablut.domain.Action;
import it.unibo.ai.didattica.competition.tablut.domain.GameDaloTablut;
import it.unibo.ai.didattica.competition.tablut.domain.State;

public class IterativeDeepeningAlphaBetaSearchTablut<S, A, P>
		/* extends IterativeDeepeningAlphaBetaSearch<S, A, P> */ implements AdversarialSearch<S, A> {

	public final static String METRICS_NODES_EXPANDED = "nodesExpanded";
	public final static String METRICS_MAX_DEPTH = "maxDepth";
	protected Metrics metrics = new Metrics();

	protected Game<S, A, P> game;
	protected double utilMax;
	protected double utilMin;
	protected int currDepthLimit;
	protected boolean heuristicEvaluationUsed; // indicates that non-terminal nodes have been evaluated.

	public boolean graphOptimization = true; // keeps references to expanded states, in order to check if the same state
												// has already been expanded
	List<Integer> expandedTopLevelStates;
	public static final int RECORD_STATES_UP_TO_DEPTH = 4;

	protected Timer timer;
	public Statistics statistics;
	protected Statistics runningStatistics;

	public boolean timedOut; // algorithm stopped search because of timeout
	public boolean outOfMemoryOccurred;

	public boolean logEnabled = false;
	public boolean printStatistics = false;
	public int maxDepth = Integer.MAX_VALUE;

	private final ExecutorService executor; // futures multi thread
	
	List<A> results;
	List<Double> utilities;

	public IterativeDeepeningAlphaBetaSearchTablut(Game<S, A, P> game, double utilMin, double utilMax, int time) {
		// super(game, utilMin, utilMax, time);
		this.game = game;
		this.utilMin = utilMin;
		this.utilMax = utilMax;
		this.timer = new Timer(time);
		this.statistics = new Statistics();
		// define executor
		executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
	}
	
	public void setMaxTime(int time) {
		this.timer = new Timer(time);
	}
	
	@Override
	public A makeDecision(S state) {
		StringBuffer logText = null;
		P player = game.getPlayer(state);
		results = game.getActions(state);
		timer.start();
		timedOut = false;
		outOfMemoryOccurred = false;
		if(timer.getDuration()>50 && maxDepth==Integer.MAX_VALUE)currDepthLimit = 3; //skip the fisrt iterations
		else currDepthLimit = 0;
		metrics = new Metrics();
		ArrayList<Future<Double>> futureValue;
		do {
			currDepthLimit++;
			if (logEnabled)
				logText = new StringBuffer("Starting search up to depth " + currDepthLimit + "\n");
			heuristicEvaluationUsed = false;
			runningStatistics = new Statistics();
			runningStatistics.reachedDepth = currDepthLimit;
			futureValue = new ArrayList<>(results.size());
			ActionStore<A> newResults = new ActionStore<>();
			if (graphOptimization) expandedTopLevelStates = new ArrayList<>(results.size());
			System.gc();
			//long startTime = System.currentTimeMillis();
			if (graphOptimization) {
				for (int i = 0; i<results.size(); i++) {  //initial move generate states all different form each other: add them to the expanded states list
					A action = results.get(i);
					S newState = game.getResult(state, action);
					expandedTopLevelStates.add(Integer.valueOf(newState.hashCode()));
				}
			}
			for (A action : results) {  //launch minMax evaluation on each possible action
				try {
					Callable<Double> callable = () -> {
						double value;
						try {
							Set<Integer> threadExpandedStates = null;
							if (graphOptimization) {
								threadExpandedStates = new TreeSet<>();
								threadExpandedStates.addAll(expandedTopLevelStates);
							}
							value = minValue(game.getResult(state, action), player, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 1, threadExpandedStates);
							if (graphOptimization) threadExpandedStates.clear();
						}catch(TimerException e){
							value = 0.0;
						}
						return value;
					};
					futureValue.add(executor.submit(callable));
				} catch (OutOfMemoryError e) {
					outOfMemoryOccurred = true;
					break;
				}
			}
			for (int i = 0; i < results.size(); i++) {
				try {
					A actionResult = results.get(i);
					double heuristicValue = futureValue.get(i).get(); //that is blocking, but threads also checks for timeout, so they terminate fast in case of timeout
					newResults.add(actionResult, heuristicValue);
				} catch (InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
				if (timer.timeOutOccurred()) { //the expansion up to this depth is not complete, so we skip getting the results
					newResults = new ActionStore<>(); //these action values are not valid: cancel them
					break;
				}
			}
			for (int i = 0; i < results.size(); i++) { //make sure to kill any remaining running thread
				if (!futureValue.get(i).isDone()) {
					futureValue.get(i).cancel(true);
				}
			}

			//long endTime = System.currentTimeMillis();
			//System.out.println("Elapsed Time: " + (endTime - startTime) + "\n");
			if (newResults.size() > 0) {
				results = newResults.actions;
				utilities = newResults.utilValues;
				runningStatistics.maxResultUtility = newResults.utilValues.get(0);
				statistics = runningStatistics;
				if (printStatistics)
					System.out.println(statistics);
				if (logEnabled)
					logText.append(
							"Action chosen: \"" + results.get(0) + "\", utility = " + newResults.utilValues.get(0)
									+ " (max possible value: " + GameDaloTablut.getMaxValueHeuristic() + ")\n");
			} else {
				if (logEnabled)
					logText.append("No action to chose from");
			}
			if (logEnabled)
				System.out.println(logText);
		} while ( // exit if:
				!timer.timeOutOccurred() && // time elapse OR
				heuristicEvaluationUsed && // heuristic not used = all evaluated states was terminal, or maximun depth has been reacked OR
				currDepthLimit < maxDepth && // currentDepth reached maxDepth
				!outOfMemoryOccurred // ram not sufficient
		);
		if (timer.timeOutOccurred())
			timedOut = true;
		return results.get(0);
	}

	public List<A> getLastResults() {
		return results;
	}
	public List<Double> getLastResultsUtilities() {
		return utilities;
	}

	// returns an utility value
	public double maxValue(S state, P player, double alpha, double beta, int depth, Set<Integer> threadExpandedStates) throws TimerException {
		runningStatistics.expandedNodes++;
		// updateMetrics(depth);
		if (timer.timeOutOccurred()) throw new TimerException();
		if (game.isTerminal(state) || depth >= currDepthLimit ) {
			return eval(state, player);
		} else {
			double value = Double.NEGATIVE_INFINITY;
			for (A action : game.getActions(state)) {
				if (timer.timeOutOccurred()) throw new TimerException();
				S newState = game.getResult(state, action);
				if (graphOptimization) {
					boolean jump = false;
					if (depth <= RECORD_STATES_UP_TO_DEPTH)
						jump = threadExpandedStates.add(Integer.valueOf(newState.hashCode())) == false;
					else
						jump = threadExpandedStates.contains(Integer.valueOf(newState.hashCode()));
					if (jump) { // this state has already been expanded by the same player, and so previously
								// evaluated. Continue with the next move
						// expandedStates.remove(newState);
						runningStatistics.skippedSameNodes++;
						continue;
					}
				}
				value = Math.max(value, minValue(newState, player, alpha, beta, depth + 1, threadExpandedStates));
				if (value >= beta)
					return value;
				alpha = Math.max(alpha, value);
			}
			return value;
		}
	}

	// returns an utility value
	public double minValue(S state, P player, double alpha, double beta, int depth, Set<Integer> threadExpandedStates) throws TimerException {
		runningStatistics.expandedNodes++;
		// updateMetrics(depth);
		if (timer.timeOutOccurred()) throw new TimerException();
		if (game.isTerminal(state) || depth >= currDepthLimit) {
			return eval(state, player);
		} else {
			double value = Double.POSITIVE_INFINITY;
			for (A action : game.getActions(state)) {
				if (timer.timeOutOccurred()) throw new TimerException();
				S newState = game.getResult(state, action);
				if (graphOptimization) {
					boolean jump = false;
					if (depth <= RECORD_STATES_UP_TO_DEPTH)
						jump = threadExpandedStates.add(Integer.valueOf(newState.hashCode())) == false;
					else
						jump = threadExpandedStates.contains(Integer.valueOf(newState.hashCode()));
					if (jump) { // this state has already been expanded by the same player, and so previously
								// evaluated. Continue with the next move
						// expandedStates.remove(newState);
						runningStatistics.skippedSameNodes++;
						continue;
					}
				}
				value = Math.min(value, maxValue(newState, player, alpha, beta, depth + 1, threadExpandedStates));
				if (value <= alpha)
					return value;
				beta = Math.min(beta, value);
			}
			return value;
		}
	}

	// @Override
	protected double eval(S state, P player) {
		// System.out.println(game.isTerminal(state));
		if (!game.isTerminal(state))
			heuristicEvaluationUsed = true;
		// System.out.println("HF: "+heuristicEvaluationUsed);
		return game.getUtility(state, player);
	}

	// empty default implementation
	protected S logExpansion(S state, A action, P player, StringBuffer logText) {
		return state;
	}

	private void updateMetrics(int depth) {
		metrics.incrementInt(METRICS_NODES_EXPANDED);
		metrics.set(METRICS_MAX_DEPTH, Math.max(metrics.getInt(METRICS_MAX_DEPTH), depth));
	}

	@Override
	public Metrics getMetrics() {
		return metrics;
	}

	///////////////////////////////////////////////////////////////////////////////////////////
	// nested helper classes

	public static class Timer {
		private long duration;
		private long startTime;

		Timer(int maxSeconds) {
			this.duration = 1000 * maxSeconds;
		}
		void start() {
			startTime = System.currentTimeMillis();
		}
		long getDuration() {
			return this.duration/1000;
		}
		boolean timeOutOccurred() {
			return System.currentTimeMillis() > startTime + duration;
		}
	}
	public static class TimerException extends Exception {}

	/**
	 * Orders actions by utility.
	 */
	public static class ActionStore<A> {
		protected List<A> actions = new ArrayList<>();
		protected List<Double> utilValues = new ArrayList<>();

		void add(A action, double utilValue) {
			int idx = 0;
			while (idx < actions.size() && utilValue <= utilValues.get(idx))
				idx++;
			actions.add(idx, action);
			utilValues.add(idx, utilValue);
		}

		int size() {
			return actions.size();
		}
	}

	public static class Statistics {
		public long expandedNodes;
		public long reachedDepth;
		public long skippedSameNodes;
		public Double maxResultUtility;
		public boolean graphOptimization;

		public Statistics() {
			expandedNodes = 0;
			reachedDepth = 0;
			skippedSameNodes = 0;
			maxResultUtility = Double.MIN_VALUE;
		}

		public String toString() {
			return "SEARCH STATISTICS:\n" + 
					"HeuristicVal: " + maxResultUtility + "\n" + 
					"expandedNodes: " + expandedNodes + "\n" + 
					"skippedSameNodes: " + skippedSameNodes + "\n" + 
					"reachedDepth: " + reachedDepth + "\n";
		}
	}
}

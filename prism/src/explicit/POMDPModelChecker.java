//==============================================================================
//	
//	Copyright (c) 2014-
//	Authors:
//	* Xueyi Zou <xz972@york.ac.uk> (University of York)
//	* Dave Parker <d.a.parker@cs.bham.ac.uk> (University of Birmingham)
//	
//------------------------------------------------------------------------------
//	
//	This file is part of PRISM.
//	
//	PRISM is free software; you can redistribute it and/or modify
//	it under the terms of the GNU General Public License as published by
//	the Free Software Foundation; either version 2 of the License, or
//	(at your option) any later version.
//	
//	PRISM is distributed in the hope that it will be useful,
//	but WITHOUT ANY WARRANTY; without even the implied warranty of
//	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//	GNU General Public License for more details.
//	
//	You should have received a copy of the GNU General Public License
//	along with PRISM; if not, write to the Free Software Foundation,
//	Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//	
//==============================================================================

package explicit;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import explicit.graphviz.Decoration;
import explicit.graphviz.Decorator;
import explicit.rewards.MDPRewards;
import explicit.rewards.StateRewardsSimple;
import prism.Accuracy;
import prism.AccuracyFactory;
import prism.Pair;
import prism.PrismComponent;
import prism.PrismException;
import prism.PrismNotSupportedException;
import prism.PrismUtils;

/**
 * Explicit-state model checker for partially observable Markov decision processes (POMDPs).
 */
public class POMDPModelChecker extends ProbModelChecker
{
	/**
	 * Create a new POMDPModelChecker, inherit basic state from parent (unless null).
	 */
	public POMDPModelChecker(PrismComponent parent) throws PrismException
	{
		super(parent);
	}

	/**
	 * Compute reachability/until probabilities.
	 * i.e. compute the min/max probability of reaching a state in {@code target},
	 * while remaining in those in @{code remain}.
	 * @param pomdp The POMDP
	 * @param remain Remain in these states (optional: null means "all")
	 * @param target Target states
	 * @param min Min or max probabilities (true=min, false=max)
	 */
	public ModelCheckerResult computeReachProbs(POMDP pomdp, BitSet target, boolean min) throws PrismException
	{
		ModelCheckerResult res = null;
		long timer;
		String stratFilename = null;

		// Check for multiple initial states 
		if (pomdp.getNumInitialStates() > 1) {
			throw new PrismNotSupportedException("POMDP model checking does not yet support multiple initial states");
		}
		
		// Start probabilistic reachability
		timer = System.currentTimeMillis();
		mainLog.println("\nStarting probabilistic reachability (" + (min ? "min" : "max") + ")...");

		// If required, create/initialise strategy storage
		if (genStrat || exportAdv) {
			stratFilename = exportAdvFilename;//"policyGraph.txt";
		}

		// Compute rewards
		res = computeReachProbsFixedGrid(pomdp, target, min, stratFilename);

		// Finished probabilistic reachability
		timer = System.currentTimeMillis() - timer;
		mainLog.println("Probabilistic reachability took " + timer / 1000.0 + " seconds.");

		// Update time taken
		res.timeTaken = timer / 1000.0;
		return res;
	}

	/**
	 * Compute expected reachability rewards using Lovejoy's fixed-resolution grid approach.
	 * Optionally, store optimal (memoryless) strategy info. 
	 * @param pomdp The POMMDP
	 * @param mdpRewards The rewards
	 * @param target Target states
	 * @param inf States for which reward is infinite
	 * @param min Min or max rewards (true=min, false=max)
	 * @param strat Storage for (memoryless) strategy choice indices (ignored if null)
	 */
	protected ModelCheckerResult computeReachProbsFixedGrid(POMDP pomdp, BitSet target, boolean min, String stratFilename) throws PrismException
	{
		// Start fixed-resolution grid approximation
		long timer = System.currentTimeMillis();
		mainLog.println("Starting fixed-resolution grid approximation (" + (min ? "min" : "max") + ")...");

		// Find out the observations for the target states
		// And determine set of observations actually need to perform computation for
		BitSet targetObs = getAndCheckTargetObservations(pomdp, target);
		BitSet unknownObs = new BitSet();
		unknownObs.set(0, pomdp.getNumObservations());
		unknownObs.andNot(targetObs);

		// Initialise the grid points (just for unknown beliefs)
		List<Belief> gridPoints = initialiseGridPoints(pomdp, unknownObs);
		mainLog.println("Grid statistics: resolution=" + gridResolution + ", points=" + gridPoints.size());
		// Construct grid belief "MDP"
		mainLog.println("Building belief space approximation...");
		List<List<HashMap<Belief, Double>>> beliefMDP = buildBeliefMDP(pomdp, gridPoints);
		
		// Initialise hashmaps for storing values for the grid belief states
		HashMap<Belief, Double> vhash = new HashMap<>();
		HashMap<Belief, Double> vhash_backUp = new HashMap<>();
		for (Belief belief : gridPoints) {
			vhash.put(belief, 0.0);
			vhash_backUp.put(belief, 0.0);
		}

		// Start iterations
		mainLog.println("Solving belief space approximation...");
		long timer2 = System.currentTimeMillis();
		double value, chosenValue;
		int iters = 0;
		boolean done = false;
		while (!done && iters < maxIters) {
			// Iterate over all (unknown) grid points
			int unK = gridPoints.size();
			for (int b = 0; b < unK; b++) {
				Belief belief = gridPoints.get(b);
				int numChoices = pomdp.getNumChoicesForObservation(belief.so);

				chosenValue = min ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
				for (int i = 0; i < numChoices; i++) {
					value = 0;
					for (Map.Entry<Belief, Double> entry : beliefMDP.get(b).get(i).entrySet()) {
						double nextBeliefProb = entry.getValue();
						Belief nextBelief = entry.getKey();
						// find discretized grid points to approximate the nextBelief
						value += nextBeliefProb * approximateReachProb(nextBelief, vhash_backUp, targetObs);
					}
					if ((min && chosenValue - value > 1.0e-6) || (!min && value - chosenValue > 1.0e-6)) {
						chosenValue = value;
					}
				}
				//update V(b) to the chosenValue
				vhash.put(belief, chosenValue);
			}
			// Check termination
			done = PrismUtils.doublesAreClose(vhash, vhash_backUp, termCritParam, termCrit == TermCrit.RELATIVE);
			// back up	
			Set<Map.Entry<Belief, Double>> entries = vhash.entrySet();
			for (Map.Entry<Belief, Double> entry : entries) {
				vhash_backUp.put(entry.getKey(), entry.getValue());
			}
			iters++;
		}
		// Non-convergence is an error (usually)
		if (!done && errorOnNonConverge) {
			String msg = "Iterative method did not converge within " + iters + " iterations.";
			msg += "\nConsider using a different numerical method or increasing the maximum number of iterations";
			throw new PrismException(msg);
		}
		timer2 = System.currentTimeMillis() - timer2;
		mainLog.print("Belief space value iteration (" + (min ? "min" : "max") + ")");
		mainLog.println(" took " + iters + " iterations and " + timer2 / 1000.0 + " seconds.");
		
		// Find discretized grid points to approximate the initialBelief
		// Also get (approximate) accuracy of result from value iteration
		Belief initialBelief = pomdp.getInitialBelief();
		double outerBound = approximateReachProb(initialBelief, vhash_backUp, targetObs);
		double outerBoundMaxDiff = PrismUtils.measureSupNorm(vhash, vhash_backUp, termCrit == TermCrit.RELATIVE);
		Accuracy outerBoundAcc = AccuracyFactory.valueIteration(termCritParam, outerBoundMaxDiff, termCrit == TermCrit.RELATIVE);
		// Print result
		mainLog.println("Outer bound: " + outerBound + " (" + outerBoundAcc.toString(outerBound) + ")");
		
		// Build DTMC to get inner bound (and strategy)
		mainLog.println("\nBuilding strategy-induced model...");
		List<Belief> listBeliefs = new ArrayList<>();
		MDP mdp = buildStrategyModel(pomdp, null, vhash, targetObs, min, listBeliefs).mdp;
		mainLog.print("Strategy-induced model: " + mdp.infoString());
		// Export?
		if (stratFilename != null) {
			mdp.exportToPrismExplicitTra(stratFilename);
			//mdp.exportToDotFile(stratFilename + ".dot", mdp.getLabelStates("target"));
			mdp.exportToDotFile(stratFilename + ".dot", Collections.singleton(new Decorator()
			{
				@Override
				public Decoration decorateState(int state, Decoration d)
				{
					d.labelAddBelow(listBeliefs.get(state).toString(pomdp));
					return d;
				}
			}));
		}
		// Create MDP model checker (disable strat generation - if enabled, we want the POMDP one) 
		MDPModelChecker mcMDP = new MDPModelChecker(this);
		mcMDP.setExportAdv(false);
		mcMDP.setGenStrat(false);
		// Solve MDP to get inner bound
		ModelCheckerResult mcRes = mcMDP.computeReachProbs(mdp, mdp.getLabelStates("target"), true);
		double innerBound = mcRes.soln[0];
		Accuracy innerBoundAcc = mcRes.accuracy;
		// Print result
		String innerBoundStr = "" + innerBound;
		if (innerBoundAcc != null) {
			innerBoundStr += " (" + innerBoundAcc.toString(innerBound) + ")";
		}
		mainLog.println("Inner bound: " + innerBoundStr);

		// Finished fixed-resolution grid approximation
		timer = System.currentTimeMillis() - timer;
		mainLog.print("\nFixed-resolution grid approximation (" + (min ? "min" : "max") + ")");
		mainLog.println(" took " + timer / 1000.0 + " seconds.");

		// Extract and store result
		Pair<Double,Accuracy> resultValAndAcc;
		if (min) {
			resultValAndAcc = AccuracyFactory.valueAndAccuracyFromInterval(outerBound, outerBoundAcc, innerBound, innerBoundAcc);
		} else {
			resultValAndAcc = AccuracyFactory.valueAndAccuracyFromInterval(innerBound, innerBoundAcc, outerBound, outerBoundAcc);
		}
		double resultVal = resultValAndAcc.first;
		Accuracy resultAcc = resultValAndAcc.second;
		mainLog.println("Result bounds: [" + resultAcc.getResultLowerBound(resultVal) + "," + resultAcc.getResultUpperBound(resultVal) + "]");
		double soln[] = new double[pomdp.getNumStates()];
		for (int initialState : pomdp.getInitialStates()) {
			soln[initialState] = resultVal;
		}

		// Return results
		ModelCheckerResult res = new ModelCheckerResult();
		res.soln = soln;
		res.accuracy = resultAcc;
		res.numIters = iters;
		res.timeTaken = timer / 1000.0;
		return res;
	}

	/**
	 * Compute expected reachability rewards.
	 * i.e. compute the min/max reward accumulated to reach a state in {@code target}.
	 * @param pomdp The POMDP
	 * @param mdpRewards The rewards
	 * @param target Target states
	 * @param min Min or max rewards (true=min, false=max)
	 */
	public ModelCheckerResult computeReachRewards(POMDP pomdp, MDPRewards mdpRewards, BitSet target, boolean min) throws PrismException
	{
		ModelCheckerResult res = null;
		long timer;
		String stratFilename = null;
		
		// Check for multiple initial states 
		if (pomdp.getNumInitialStates() > 1) {
			throw new PrismNotSupportedException("POMDP model checking does not yet support multiple initial states");
		}
		
		// Start expected reachability
		timer = System.currentTimeMillis();
		mainLog.println("\nStarting expected reachability (" + (min ? "min" : "max") + ")...");

		// If required, create/initialise strategy storage
		if (genStrat || exportAdv) {
			stratFilename = exportAdvFilename;
		}

		// Compute rewards
		res = computeReachRewardsFixedGrid(pomdp, mdpRewards, target, min, stratFilename);

		// Finished expected reachability
		timer = System.currentTimeMillis() - timer;
		mainLog.println("Expected reachability took " + timer / 1000.0 + " seconds.");

		// Update time taken
		res.timeTaken = timer / 1000.0;
		return res;
	}

	/**
	 * Compute expected reachability rewards using Lovejoy's fixed-resolution grid approach.
	 * Optionally, store optimal (memoryless) strategy info. 
	 * @param pomdp The POMMDP
	 * @param mdpRewards The rewards
	 * @param target Target states
	 * @param inf States for which reward is infinite
	 * @param min Min or max rewards (true=min, false=max)
	 * @param strat Storage for (memoryless) strategy choice indices (ignored if null)
	 */
	protected ModelCheckerResult computeReachRewardsFixedGrid(POMDP pomdp, MDPRewards mdpRewards, BitSet target, boolean min, String stratFilename) throws PrismException
	{
		// Start fixed-resolution grid approximation
		long timer = System.currentTimeMillis();
		mainLog.println("Starting fixed-resolution grid approximation (" + (min ? "min" : "max") + ")...");

		// Find out the observations for the target states
		// And determine set of observations actually need to perform computation for
		BitSet targetObs = getAndCheckTargetObservations(pomdp, target);
		BitSet unknownObs = new BitSet();
		unknownObs.set(0, pomdp.getNumObservations());
		unknownObs.andNot(targetObs);

		// Initialise the grid points (just for unknown beliefs)
		List<Belief> gridPoints = initialiseGridPoints(pomdp, unknownObs);
		mainLog.println("Grid statistics: resolution=" + gridResolution + ", points=" + gridPoints.size());
		// Construct grid belief "MDP"
		mainLog.println("Building belief space approximation...");
		List<List<HashMap<Belief, Double>>> beliefMDP = buildBeliefMDP(pomdp, gridPoints);
		
		// Rewards
		List<List<Double>> rewards = new ArrayList<>(); // memoization for reuse
		int unK = gridPoints.size();
		for (int b = 0; b < unK; b++) {
			Belief belief = gridPoints.get(b);
			int numChoices = pomdp.getNumChoicesForObservation(belief.so);
			List<Double> action_reward = new ArrayList<>();// for memoization
			for (int i = 0; i < numChoices; i++) {
				action_reward.add(pomdp.getRewardAfterChoice(belief, i, mdpRewards)); // c(a,b)
			}
			rewards.add(action_reward);
		}
		
		// Initialise hashmaps for storing values for the grid belief states
		HashMap<Belief, Double> vhash = new HashMap<>();
		HashMap<Belief, Double> vhash_backUp = new HashMap<>();
		for (Belief belief : gridPoints) {
			vhash.put(belief, 0.0);
			vhash_backUp.put(belief, 0.0);
		}
		
		// Start iterations
		mainLog.println("Solving belief space approximation...");
		long timer2 = System.currentTimeMillis();
		double value, chosenValue;
		int iters = 0;
		boolean done = false;
		while (!done && iters < maxIters) {
			// Iterate over all (unknown) grid points
			for (int b = 0; b < unK; b++) {
				Belief belief = gridPoints.get(b);
				int numChoices = pomdp.getNumChoicesForObservation(belief.so);
				chosenValue = min ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
				for (int i = 0; i < numChoices; i++) {
					value = rewards.get(b).get(i);
					for (Map.Entry<Belief, Double> entry : beliefMDP.get(b).get(i).entrySet()) {
						double nextBeliefProb = entry.getValue();
						Belief nextBelief = entry.getKey();
						// find discretized grid points to approximate the nextBelief
						value += nextBeliefProb * approximateReachReward(nextBelief, vhash_backUp, targetObs);
					}
					if ((min && chosenValue - value > 1.0e-6) || (!min && value - chosenValue > 1.0e-6)) {
						chosenValue = value;
					}
				}
				//update V(b) to the chosenValue
				vhash.put(belief, chosenValue);
			}
			// Check termination
			done = PrismUtils.doublesAreClose(vhash, vhash_backUp, termCritParam, termCrit == TermCrit.RELATIVE);
			// back up	
			Set<Map.Entry<Belief, Double>> entries = vhash.entrySet();
			for (Map.Entry<Belief, Double> entry : entries) {
				vhash_backUp.put(entry.getKey(), entry.getValue());
			}
			iters++;
		}
		// Non-convergence is an error (usually)
		if (!done && errorOnNonConverge) {
			String msg = "Iterative method did not converge within " + iters + " iterations.";
			msg += "\nConsider using a different numerical method or increasing the maximum number of iterations";
			throw new PrismException(msg);
		}
		timer2 = System.currentTimeMillis() - timer2;
		mainLog.print("Belief space value iteration (" + (min ? "min" : "max") + ")");
		mainLog.println(" took " + iters + " iterations and " + timer2 / 1000.0 + " seconds.");

		// Find discretized grid points to approximate the initialBelief
		// Also get (approximate) accuracy of result from value iteration
		Belief initialBelief = pomdp.getInitialBelief();
		double outerBound = approximateReachReward(initialBelief, vhash_backUp, targetObs);
		double outerBoundMaxDiff = PrismUtils.measureSupNorm(vhash, vhash_backUp, termCrit == TermCrit.RELATIVE);
		Accuracy outerBoundAcc = AccuracyFactory.valueIteration(termCritParam, outerBoundMaxDiff, termCrit == TermCrit.RELATIVE);
		// Print result
		mainLog.println("Outer bound: " + outerBound + " (" + outerBoundAcc.toString(outerBound) + ")");
		
		// Build DTMC to get inner bound (and strategy)
		mainLog.println("\nBuilding strategy-induced model...");
		List<Belief> listBeliefs = new ArrayList<>();
		POMDPStrategyModel psm = buildStrategyModel(pomdp, mdpRewards, vhash, targetObs, min, listBeliefs);
		MDP mdp = psm.mdp;
		MDPRewards mdpRewardsNew = psm.mdpRewards;
		mainLog.print("Strategy-induced model: " + mdp.infoString());
		// Export?
		if (stratFilename != null) {
			mdp.exportToPrismExplicitTra(stratFilename);
			//mdp.exportToDotFile(stratFilename + ".dot", mdp.getLabelStates("target"));
			mdp.exportToDotFile(stratFilename + ".dot", Collections.singleton(new Decorator()
			{
				@Override
				public Decoration decorateState(int state, Decoration d)
				{
					d.labelAddBelow(listBeliefs.get(state).toString(pomdp));
					return d;
				}
			}));
		}

		// Create MDP model checker (disable strat generation - if enabled, we want the POMDP one) 
		MDPModelChecker mcMDP = new MDPModelChecker(this);
		mcMDP.setExportAdv(false);
		mcMDP.setGenStrat(false);
		// Solve MDP to get inner bound
		ModelCheckerResult mcRes = mcMDP.computeReachRewards(mdp, mdpRewardsNew, mdp.getLabelStates("target"), true);
		double innerBound = mcRes.soln[0];
		Accuracy innerBoundAcc = mcRes.accuracy;
		// Print result
		String innerBoundStr = "" + innerBound;
		if (innerBoundAcc != null) {
			innerBoundStr += " (" + innerBoundAcc.toString(innerBound) + ")";
		}
		mainLog.println("Inner bound: " + innerBoundStr);

		// Finished fixed-resolution grid approximation
		timer = System.currentTimeMillis() - timer;
		mainLog.print("\nFixed-resolution grid approximation (" + (min ? "min" : "max") + ")");
		mainLog.println(" took " + timer / 1000.0 + " seconds.");

		// Extract and store result
		Pair<Double,Accuracy> resultValAndAcc;
		if (min) {
			resultValAndAcc = AccuracyFactory.valueAndAccuracyFromInterval(outerBound, outerBoundAcc, innerBound, innerBoundAcc);
		} else {
			resultValAndAcc = AccuracyFactory.valueAndAccuracyFromInterval(innerBound, innerBoundAcc, outerBound, outerBoundAcc);
		}
		double resultVal = resultValAndAcc.first;
		Accuracy resultAcc = resultValAndAcc.second;
		mainLog.println("Result bounds: [" + resultAcc.getResultLowerBound(resultVal) + "," + resultAcc.getResultUpperBound(resultVal) + "]");
		double soln[] = new double[pomdp.getNumStates()];
		for (int initialState : pomdp.getInitialStates()) {
			soln[initialState] = resultVal;
		}

		// Return results
		ModelCheckerResult res = new ModelCheckerResult();
		res.soln = soln;
		res.accuracy = resultAcc;
		res.numIters = iters;
		res.timeTaken = timer / 1000.0;
		return res;
	}

	/**
	 * Get a list of target observations from a set of target states
	 * (both are represented by BitSets over their indices).
	 * Also check that the set of target states corresponds to a set
	 * of observations, and throw an exception if not.
	 */
	protected BitSet getAndCheckTargetObservations(POMDP pomdp, BitSet target) throws PrismException
	{
		// Find observations corresponding to each state in the target
		BitSet targetObs = new BitSet();
		for (int s = target.nextSetBit(0); s >= 0; s = target.nextSetBit(s + 1)) {
			targetObs.set(pomdp.getObservation(s));
		}
		// Recreate the set of target states from the target observations and make sure it matches
		BitSet target2 = new BitSet();
		int numStates = pomdp.getNumStates();
		for (int s = 0; s < numStates; s++) {
			if (targetObs.get(pomdp.getObservation(s))) {
				target2.set(s);
			}
		}
		if (!target.equals(target2)) {
			throw new PrismException("Target is not observable");
		}
		return targetObs;
	}
	
	/**
	 * Construct a list of beliefs for a grid-based approximation of the belief space.
	 * Only beliefs with observable values from {@code unknownObs) are added.
	 */
	protected List<Belief> initialiseGridPoints(POMDP pomdp, BitSet unknownObs)
	{
		List<Belief> gridPoints = new ArrayList<>();
		ArrayList<ArrayList<Double>> assignment;
		int numUnobservations = pomdp.getNumUnobservations();
		int numStates = pomdp.getNumStates();
		for (int so = unknownObs.nextSetBit(0); so >= 0; so = unknownObs.nextSetBit(so + 1)) {
			ArrayList<Integer> unobservsForObserv = new ArrayList<>();
			for (int s = 0; s < numStates; s++) {
				if (so == pomdp.getObservation(s)) {
					unobservsForObserv.add(pomdp.getUnobservation(s));
				}
			}
			assignment = fullAssignment(unobservsForObserv.size(), gridResolution);
			for (ArrayList<Double> inner : assignment) {
				double[] bu = new double[numUnobservations];
				int k = 0;
				for (int unobservForObserv : unobservsForObserv) {
					bu[unobservForObserv] = inner.get(k);
					k++;
				}
				gridPoints.add(new Belief(so, bu));
			}
		}
		return gridPoints;
	}
	
	/**
	 * Construct (part of) a belief MDP, just for the set of passed in belief states.
	 * It is stored as a list (over source beliefs) of lists (over choices)
	 * of distributions over target beliefs, stored as a hashmap.
	 */
	protected List<List<HashMap<Belief, Double>>> buildBeliefMDP(POMDP pomdp, List<Belief> beliefs)
	{
		List<List<HashMap<Belief, Double>>> beliefMDP = new ArrayList<>();
		for (Belief belief: beliefs) {
			beliefMDP.add(buildBeliefMDPState(pomdp, belief));
		}
		return beliefMDP;
	}
	
	/**
	 * Construct a single single state (belief) of a belief MDP, stored as a
	 * list (over choices) of distributions over target beliefs, stored as a hashmap.
	 */
	protected List<HashMap<Belief, Double>> buildBeliefMDPState(POMDP pomdp, Belief belief)
	{
		double[] beliefInDist = belief.toDistributionOverStates(pomdp);
		List<HashMap<Belief, Double>> beliefMDPState = new ArrayList<>();
		// And for each choice
		int numChoices = pomdp.getNumChoicesForObservation(belief.so);
		for (int i = 0; i < numChoices; i++) {
			// Get successor observations and their probs
			HashMap<Integer, Double> obsProbs = pomdp.computeObservationProbsAfterAction(beliefInDist, i);
			HashMap<Belief, Double> beliefDist = new HashMap<>();
			// Find the belief for each observations
			for (Map.Entry<Integer, Double> entry : obsProbs.entrySet()) {
				int o = entry.getKey();
				Belief nextBelief = pomdp.getBeliefAfterChoiceAndObservation(belief, i, o);
				beliefDist.put(nextBelief, entry.getValue());
			}
			beliefMDPState.add(beliefDist);
		}
		return beliefMDPState;
	}
	
	/**
	 * Compute the grid-based approximate value for a belief for probabilistic reachability
	 */
	protected double approximateReachProb(Belief belief, HashMap<Belief, Double> gridValues, BitSet targetObs)
	{
		// 1 for target states
		if (targetObs.get(belief.so)) {
			return 1.0;
		}
		// Otherwise approximate vie interpolation over grid points
		else {
			return interpolateOverGrid(belief, gridValues);
		}
	}
	
	/**
	 * Compute the grid-based approximate value for a belief for reward reachability
	 */
	protected double approximateReachReward(Belief belief, HashMap<Belief, Double> gridValues, BitSet targetObs)
	{
		// 0 for target states
		if (targetObs.get(belief.so)) {
			return 0.0;
		}
		// Otherwise approximate vie interpolation over grid points
		else {
			return interpolateOverGrid(belief, gridValues);
		}
	}
	
	/**
	 * Approximate the value for a belief {@code belief} by interpolating over values {@code gridValues}
	 * for a representative set of beliefs whose convex hull is the full belief space.
	 */
	protected double interpolateOverGrid(Belief belief, HashMap<Belief, Double> gridValues)
	{
		ArrayList<double[]> subSimplex = new ArrayList<>();
		double[] lambdas = new double[belief.bu.length];
		getSubSimplexAndLambdas(belief.bu, subSimplex, lambdas, gridResolution);
		double val = 0;
		for (int j = 0; j < lambdas.length; j++) {
			if (lambdas[j] >= 1e-6) {
				val += lambdas[j] * gridValues.get(new Belief(belief.so, subSimplex.get(j)));
			}
		}
		return val;
	}
	
	class POMDPStrategyModel
	{
		public MDP mdp;
		public MDPRewards mdpRewards;
	}
	
	/**
	 * Build a (Markov chain) model representing the fragment of the belief MDP induced by an optimal strategy.
	 * The model is stored as an MDP to allow easier attachment of optional actions.
	 * @param pomdp
	 * @param mdpRewards
	 * @param vhash
	 * @param vhash_backUp
	 * @param target
	 * @param min
	 * @param listBeliefs
	 */
	protected POMDPStrategyModel buildStrategyModel(POMDP pomdp, MDPRewards mdpRewards, HashMap<Belief, Double> vhash, BitSet targetObs, boolean min, List<Belief> listBeliefs)
	{
		// Initialise model/state/rewards storage
		MDPSimple mdp = new MDPSimple();
		IndexedSet<Belief> exploredBelieves = new IndexedSet<>(true);
		LinkedList<Belief> toBeExploredBelives = new LinkedList<>();
		BitSet mdpTarget = new BitSet();
		StateRewardsSimple stateRewards = new StateRewardsSimple();
		// Add initial state
		Belief initialBelief = pomdp.getInitialBelief();
		exploredBelieves.add(initialBelief);
		toBeExploredBelives.offer(initialBelief);
		mdp.addState();
		mdp.addInitialState(0);
		
		// Explore model
		int src = -1;
		while (!toBeExploredBelives.isEmpty()) {
			Belief b = toBeExploredBelives.pollFirst();
			src++;
			
			if (targetObs.get(b.so)) {
				mdpTarget.set(src);
			} else {
				extractBestActions(src, b, vhash, targetObs, pomdp, mdpRewards, min, exploredBelieves, toBeExploredBelives, mdp, stateRewards);
			}
		}
		// Attach a label marking target states
		mdp.addLabel("target", mdpTarget);
		listBeliefs.addAll(exploredBelieves.toArrayList());
		// Return
		POMDPStrategyModel psm = new POMDPStrategyModel();
		psm.mdp = mdp;
		psm.mdpRewards = stateRewards;
		return psm;
	}
	
	/**
	 * Find the best action for this belief state, add the belief state to the list
	 * of ones examined so far, and store the strategy info. We store this as an MDP.
	 * @param belief Belief state to examine
	 * @param vhash
	 * @param pomdp
	 * @param mdpRewards
	 * @param min
	 * @param beliefList
	 */
	protected void extractBestActions(int src, Belief belief, HashMap<Belief, Double> vhash, BitSet targetObs, POMDP pomdp, MDPRewards mdpRewards, boolean min,
			IndexedSet<Belief> exploredBelieves, LinkedList<Belief> toBeExploredBelives, MDPSimple mdp, StateRewardsSimple stateRewards)
	{
		double chosenValue = min ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
		int chosenActionIndex = -1;
		//evaluate each action in b
		int numChoices = pomdp.getNumChoicesForObservation(belief.so);
		List<HashMap<Belief, Double>> beliefMDPState = buildBeliefMDPState(pomdp, belief);
		for (int a = 0; a < numChoices; a++) {
			double value = 0;
			if (mdpRewards != null) {
				value = pomdp.getRewardAfterChoice(belief, a, mdpRewards); // c(a,b)
			}
			for (Map.Entry<Belief, Double> entry : beliefMDPState.get(a).entrySet()) {
				double nextBeliefProb = entry.getValue();
				Belief nextBelief = entry.getKey();
				if (mdpRewards == null) {
					value += nextBeliefProb * approximateReachProb(nextBelief, vhash, targetObs);
				} else {
					value += nextBeliefProb * approximateReachReward(nextBelief, vhash, targetObs);
				}
			}
			
			//select action that minimizes/maximizes Q(a,b), i.e. value
			if ((min && chosenValue - value > 1.0e-6) || (!min && value - chosenValue > 1.0e-6))//value<bestValue
			{
				chosenValue = value;
				chosenActionIndex = a;
			} else if (Math.abs(value - chosenValue) < 1.0e-6)//value==chosenValue
			{
				//random tie broker
				chosenActionIndex = Math.random() < 0.5 ? a : chosenActionIndex;
				chosenActionIndex = a;
			}
		}
		// Build a distribution over successor belief states and add to MDP 
		Distribution distr = new Distribution();
		for (Map.Entry<Belief, Double> entry : beliefMDPState.get(chosenActionIndex).entrySet()) {
			double nextBeliefProb = entry.getValue();
			Belief nextBelief = entry.getKey();
			// Add each successor belief to the MDP and the "to explore" set if new
			if (exploredBelieves.add(nextBelief)) {
				toBeExploredBelives.add(nextBelief);
				mdp.addState();
			}
			// Get index of state in state set
			int dest = exploredBelieves.getIndexOfLastAdd();
			distr.add(dest, nextBeliefProb);
		}
		// Add transition distribution, with choice _index_ encoded as action
		mdp.addActionLabelledChoice(src, distr, pomdp.getActionForObservation(belief.so, chosenActionIndex));
		// Store reward too, if needed
		if (mdpRewards != null) {
			stateRewards.setStateReward(src, pomdp.getRewardAfterChoice(belief, chosenActionIndex, mdpRewards));
		}
	}
	
	protected ArrayList<ArrayList<Integer>> assignGPrime(int startIndex, int min, int max, int length)
	{
		ArrayList<ArrayList<Integer>> result = new ArrayList<ArrayList<Integer>>();
		if (startIndex == length - 1) {
			for (int i = min; i <= max; i++) {
				ArrayList<Integer> innerList = new ArrayList<>();
				innerList.add(i);
				result.add(innerList);
			}
		} else {
			for (int i = min; i <= max; i++) {
				ArrayList<ArrayList<Integer>> nextResult = assignGPrime(startIndex + 1, 0, i, length);
				for (ArrayList<Integer> nextReulstInner : nextResult) {
					ArrayList<Integer> innerList = new ArrayList<>();
					innerList.add(i);
					for (Integer a : nextReulstInner) {
						innerList.add(a);
					}
					result.add(innerList);
				}
			}
		}

		return result;
	}

	private ArrayList<ArrayList<Double>> fullAssignment(int length, int resolution)
	{
		ArrayList<ArrayList<Integer>> GPrime = assignGPrime(0, resolution, resolution, length);
		ArrayList<ArrayList<Double>> result = new ArrayList<ArrayList<Double>>();
		for (ArrayList<Integer> GPrimeInner : GPrime) {
			ArrayList<Double> innerList = new ArrayList<>();
			int i;
			for (i = 0; i < length - 1; i++) {
				int temp = GPrimeInner.get(i) - GPrimeInner.get(i + 1);
				innerList.add((double) temp / resolution);
			}
			innerList.add((double) GPrimeInner.get(i) / resolution);
			result.add(innerList);
		}
		return result;
	}

	private int[] getSortedPermutation(double[] inputArray)
	{
		int n = inputArray.length;
		double[] inputCopy = new double[n];
		int[] permutation = new int[n];
		int iState = 0, iIteration = 0;
		int iNonZeroEntry = 0, iZeroEntry = n - 1;
		boolean bDone = false;

		for (iState = n - 1; iState >= 0; iState--) {
			if (inputArray[iState] == 0.0) {
				inputCopy[iZeroEntry] = 0.0;
				permutation[iZeroEntry] = iState;
				iZeroEntry--;
			}

		}

		for (iState = 0; iState < n; iState++) {
			if (inputArray[iState] != 0.0) {
				inputCopy[iNonZeroEntry] = inputArray[iState];
				permutation[iNonZeroEntry] = iState;
				iNonZeroEntry++;
			}
		}

		while (!bDone) {
			bDone = true;
			for (iState = 0; iState < iNonZeroEntry - iIteration - 1; iState++) {
				if (inputCopy[iState] < inputCopy[iState + 1]) {
					swap(inputCopy, iState, iState + 1);
					swap(permutation, iState, iState + 1);
					bDone = false;
				}
			}
			iIteration++;
		}

		return permutation;
	}

	private void swap(int[] aiArray, int i, int j)
	{
		int temp = aiArray[i];
		aiArray[i] = aiArray[j];
		aiArray[j] = temp;
	}

	private void swap(double[] aiArray, int i, int j)
	{
		double temp = aiArray[i];
		aiArray[i] = aiArray[j];
		aiArray[j] = temp;
	}

	protected boolean getSubSimplexAndLambdas(double[] b, ArrayList<double[]> subSimplex, double[] lambdas, int resolution)
	{
		int n = b.length;
		int M = resolution;

		double[] X = new double[n];
		int[] V = new int[n];
		double[] D = new double[n];
		for (int i = 0; i < n; i++) {
			X[i] = 0;
			for (int j = i; j < n; j++) {
				X[i] += M * b[j];
			}
			X[i] = Math.round(X[i] * 1e6) / 1e6;
			V[i] = (int) Math.floor(X[i]);
			D[i] = X[i] - V[i];
		}

		int[] P = getSortedPermutation(D);
		//		mainLog.println("X: "+ Arrays.toString(X));
		//		mainLog.println("V: "+ Arrays.toString(V));
		//		mainLog.println("D: "+ Arrays.toString(D));
		//		mainLog.println("P: "+ Arrays.toString(P));

		ArrayList<int[]> Qs = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int[] Q = new int[n];
			if (i == 0) {
				for (int j = 0; j < n; j++) {
					Q[j] = V[j];
				}
				Qs.add(Q);
			} else {
				for (int j = 0; j < n; j++) {
					if (j == P[i - 1]) {
						Q[j] = Qs.get(i - 1)[j] + 1;
					} else {
						Q[j] = Qs.get(i - 1)[j];
					}

				}
				Qs.add(Q);
			}
			//			mainLog.println(Arrays.toString(Q));
		}

		for (int[] Q : Qs) {
			double[] node = new double[n];
			int i;
			for (i = 0; i < n - 1; i++) {
				int temp = Q[i] - Q[i + 1];
				node[i] = (double) temp / M;
			}
			node[i] = (double) Q[i] / M;
			subSimplex.add(node);
		}

		double sum = 0;
		for (int i = 1; i < n; i++) {
			double lambda = D[P[i - 1]] - D[P[i]];
			lambdas[i] = lambda;
			sum = sum + lambda;
		}
		lambdas[0] = 1 - sum;

		for (int i = 0; i < n; i++) {
			double sum2 = 0;
			for (int j = 0; j < n; j++) {
				sum2 += lambdas[j] * subSimplex.get(j)[i];
			}
			//			mainLog.println("b["+i+"]: "+b[i]+"  b^[i]:"+sum2);
			if (Math.abs(b[i] - sum2) > 1e-4) {
				return false;
			}

		}
		return true;
	}

	public static boolean isTargetBelief(double[] belief, BitSet target)
	{
		 double prob=0;
		 for (int i = target.nextSetBit(0); i >= 0; i = target.nextSetBit(i+1)) 
		 {
			 prob+=belief[i];
		 }
		 if(Math.abs(prob-1.0)<1.0e-6)
		 {
			 return true;
		 }
		 return false;
	}	

	/**
	 * Simple test program.
	 */
	public static void main(String args[])
	{
		POMDPModelChecker mc;
		POMDPSimple pomdp;
		ModelCheckerResult res;
		BitSet init, target;
		Map<String, BitSet> labels;
		boolean min = true;
		try {
			mc = new POMDPModelChecker(null);
			MDPSimple mdp = new MDPSimple();
			mdp.buildFromPrismExplicit(args[0]);
			//mainLog.println(mdp);
			labels = mc.loadLabelsFile(args[1]);
			//mainLog.println(labels);
			init = labels.get("init");
			target = labels.get(args[2]);
			if (target == null)
				throw new PrismException("Unknown label \"" + args[2] + "\"");
			for (int i = 3; i < args.length; i++) {
				if (args[i].equals("-min"))
					min = true;
				else if (args[i].equals("-max"))
					min = false;
				else if (args[i].equals("-nopre"))
					mc.setPrecomp(false);
			}
			pomdp = new POMDPSimple(mdp);
			res = mc.computeReachRewards(pomdp, null, target, min);
			System.out.println(res.soln[init.nextSetBit(0)]);
		} catch (PrismException e) {
			System.out.println(e);
		}
	}
}
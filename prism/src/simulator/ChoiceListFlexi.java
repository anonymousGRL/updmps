//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Dave Parker <david.parker@comlab.ox.ac.uk> (University of Oxford)
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

package simulator;

import java.util.ArrayList;
import java.util.List;

import parser.State;
import parser.VarList;
import parser.ast.Command;
import parser.ast.Expression;
import parser.ast.Update;
import prism.ModelType;
import prism.PrismException;
import prism.PrismLangException;

/**
 * A mutable implementation of {@link simulator.Choice},
 * i.e, a representation of a single (nondeterministic) choice in a PRISM model,
 * in the form of a list of transitions, each specified by updates to variables.
 */
public class ChoiceListFlexi implements Choice
{
	// Module/action info, encoded as an integer.
	// For an independent (non-synchronous) choice, this is -i,
	// where i is the 1-indexed module index.
	// For a synchronous choice, this is the 1-indexed action index.
	protected int moduleOrActionIndex;

	// List of multiple updates and associated probabilities/rates
	// Size of list is stored implicitly in target.length
	// Probabilities/rates are already evaluated, target states are not
	// but are just stored as lists of updates (for efficiency)
	protected List<List<Update>> updates;
	protected List<Double> probability;
	
	// For real-time models, the clock guard,
	// i.e., an expression over clock variables
	// denoting when it can be taken.
	protected Expression clockGuard;

	/**
	 * Create empty choice.
	 */
	public ChoiceListFlexi()
	{
		updates = new ArrayList<List<Update>>();
		probability = new ArrayList<Double>();
		clockGuard = null;
	}

	/**
	 * Copy constructor.
	 * NB: Does a shallow, not deep, copy with respect to references to Update objects.
	 */
	public ChoiceListFlexi(ChoiceListFlexi ch)
	{
		moduleOrActionIndex = ch.moduleOrActionIndex;
		updates = new ArrayList<List<Update>>(ch.updates.size());
		for (List<Update> list : ch.updates) {
			List<Update> listNew = new ArrayList<Update>(list.size()); 
			updates.add(listNew);
			for (Update up : list) {
				listNew.add(up);
			}
		}
		probability = new ArrayList<Double>(ch.size());
		for (double p : ch.probability) {
			probability.add(p);
		}
		clockGuard = ch.clockGuard;
	}

	// Set methods

	/**
	 * Set the module/action for this choice, encoded as an integer
	 * (-i for independent in ith module, i for synchronous on ith action)
	 * (in both cases, modules/actions are 1-indexed)
	 */
	public void setModuleOrActionIndex(int moduleOrActionIndex)
	{
		this.moduleOrActionIndex = moduleOrActionIndex;
	}

	/**
	 * Set the clock guard
	 */
	public void setClockGuard(Expression clockGuard)
	{
		this.clockGuard = clockGuard;
	}

	/**
	 * Add a transition to this choice.
	 * @param probability Probability (or rate) of the transition
	 * @param ups List of Update objects defining transition
	 */
	public void add(double probability, List<Update> ups)
	{
		this.updates.add(ups);
		this.probability.add(probability);
	}

	@Override
	public void scaleProbabilitiesBy(double d)
	{
		int i, n;
		n = size();
		for (i = 0; i < n; i++) {
			probability.set(i, probability.get(i) * d);
		}
	}

	/**
	 * Modify this choice, constructing product of it with another.
	 */
	public void productWith(ChoiceListFlexi ch)
	{
		List<Update> list;
		int i, j, n, n2;
		double pi;

		n = ch.size();
		n2 = size();
		// Loop through each (ith) element of new choice (skipping first)
		for (i = 1; i < n; i++) {
			pi = ch.getProbability(i);
			// Loop through each (jth) element of existing choice
			for (j = 0; j < n2; j++) {
				// Create new element (i,j) of product 
				list = new ArrayList<Update>(updates.get(j).size() + ch.updates.get(i).size());
				for (Update u : updates.get(j)) {
					list.add(u);
				}
				for (Update u : ch.updates.get(i)) {
					list.add(u);
				}
				add(pi * getProbability(j), list);
			}
		}
		// Modify elements of current choice to get (0,j) elements of product
		pi = ch.getProbability(0);
		for (j = 0; j < n2; j++) {
			for (Update u : ch.updates.get(0)) {
				updates.get(j).add(u);
			}
			probability.set(j, pi * probability.get(j));
		}
		if (ch.clockGuard != null) {
			clockGuard = (clockGuard == null) ? ch.clockGuard : Expression.And(clockGuard, ch.clockGuard);
		}
	}

	// Get methods

	@Override
	public int getModuleOrActionIndex()
	{
		return moduleOrActionIndex;
	}

	@Override
	public String getModuleOrAction()
	{
		// Action label (or absence of) will be the same for all updates in a choice
		Update u = updates.get(0).get(0);
		Command c = u.getParent().getParent();
		if ("".equals(c.getSynch()))
			return c.getParent().getName();
		else
			return "[" + c.getSynch() + "]";
	}

	@Override
	public Expression getClockGuard()
	{
		return clockGuard;
	}
	
	@Override
	public int size()
	{
		return probability.size();
	}

	@Override
	public String getUpdateString(int i, State currentState) throws PrismLangException
	{
		int j, n;
		String s = "";
		boolean first = true;
		for (Update up : updates.get(i)) {
			n = up.getNumElements();
			for (j = 0; j < n; j++) {
				if (first)
					first = false;
				else
					s += ", ";
				s += up.getVar(j) + "'=" + up.getExpression(j).evaluate(currentState);
			}
		}
		return s;
	}

	@Override
	public String getUpdateStringFull(int i)
	{
		String s = "";
		boolean first = true;
		for (Update up : updates.get(i)) {
			if (up.getNumElements() == 0)
				continue;
			if (first)
				first = false;
			else
				s += " & ";
			s += up;
		}
		return s;
	}

	@Override
	public State computeTarget(int i, State currentState) throws PrismLangException
	{
		State newState = new State(currentState);
		for (Update up : updates.get(i))
			up.update(currentState, newState);
		return newState;
	}

	@Override
	public void computeTarget(int i, State currentState, State newState) throws PrismLangException
	{
		for (Update up : updates.get(i))
			up.update(currentState, newState);
	}

	@Override
	public double getProbability(int i)
	{
		return probability.get(i);
	}

	@Override
	public double getProbabilitySum()
	{
		double sum = 0.0;
		for (double d : probability)
			sum += d;
		return sum;
	}

	@Override
	public int getIndexByProbabilitySum(double x)
	{
		int i, n;
		double d;
		n = size();
		d = 0.0;
		for (i = 0; x >= d && i < n; i++) {
			d += probability.get(i);
		}
		return i - 1;
	}

	@Override
	public void checkValid(ModelType modelType) throws PrismException
	{
		// Currently nothing to do here:
		// Checks for bad probabilities/rates done earlier.
	}
	
	@Override
	public void checkForErrors(State currentState, VarList varList) throws PrismException
	{
		int i, n;
		n = size();
		for (i = 0; i < n; i++) {
			for (Update up : updates.get(i))
				up.checkUpdate(currentState, varList);
		}
	}
	
	@Override
	public String toString()
	{
		int i, n;
		boolean first = true;
		String s = "";
		if (clockGuard != null) {
			s += "(" + clockGuard + ")";
		}
		n = size();
		for (i = 0; i < n; i++) {
			if (first)
				first = false;
			else
				s += " + ";
			s += getProbability(i) + ":" + updates.get(i);
		}
		return s;
	}
}

//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Dave Parker <d.a.parker@cs.bham.ac.uk> (University of Birmingham/Oxford)
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

import java.util.BitSet;
import java.util.List;

import prism.PrismComponent;
import prism.PrismException;

/**
 * Abstract class for (explicit) classes that compute (M)ECs, i.e. (maximal) end components,
 * for a nondeterministic model such as an MDP.
 */
public abstract class ECComputer extends PrismComponent
{
	/**
	 * Static method to create a new ECComputer object, depending on current settings.
	 */
	public static ECComputer createECComputer(PrismComponent parent, NondetModel model) throws PrismException
	{
		// Only one algorithm implemented currently
		return new ECComputerDefault(parent, model);
	}

	/**
	 * Base constructor.
	 */
	public ECComputer(PrismComponent parent) throws PrismException
	{
		super(parent);
	}

	/**
	 * Compute states of maximal end components (MECs) and store them.
	 * They can be retrieved using {@link #getMECStates()}.
	 */
	public abstract void computeMECStates() throws PrismException;

	/**
	 * Compute states of all maximal end components (MECs) contained within {@code states}, and store them.
	 * They can be retrieved using {@link #getMECStates()}.
	 * @param states BitSet for the set of containing states
	 */
	public abstract void computeMECStates(BitSet states) throws PrismException;

	/**
	 * Get the list of states for computed MECs.
	 */
	public abstract List<BitSet> getMECStates();
}

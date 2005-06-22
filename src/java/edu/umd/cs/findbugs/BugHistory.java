/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2005, University of Maryland
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
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.dom4j.DocumentException;

import edu.umd.cs.findbugs.config.CommandLine;

/**
 * Analyze bug results to find new, fixed, and retained bugs
 * between versions of the same program.  Uses VersionInsensitiveBugComparator
 * (or FuzzyBugComparator)
 * to determine when two BugInstances are the "same".
 * The new BugCollection returned is a deep copy of one of the input collections
 * (depending on the operation performed), with only a subset of the original
 * BugInstances retained.  Because it is a deep copy, it may be freely modified.
 * 
 * @author David Hovemeyer
 */
public class BugHistory {
	private static final boolean DEBUG = false;
	
	/**
	 * A set operation between two bug collections.
	 */
	public interface SetOperation {
		/**
		 * Perform the set operation.
		 * 
		 * @param result         Set to put the resulting BugInstances in
		 * @param origCollection original BugCollection
		 * @param newCollection  new BugCollection
		 * @returns the input bug collection the results are taken from
		 */
		public SortedBugCollection perform(Set<BugInstance> result,
				SortedBugCollection origCollection, SortedBugCollection newCollection);
	}
	
	/**
	 * Get the warnings which were <em>added</em>,
	 * meaning that they were not part of the original BugCollection.
	 * The BugInstances returned are from the new BugCollection.
	 */
	public static final SetOperation ADDED_WARNINGS = new SetOperation(){
		public SortedBugCollection perform(Set<BugInstance> result,
				SortedBugCollection origCollection, SortedBugCollection newCollection) {
			result.addAll(newCollection.getCollection());

			// Get shared instances
			List<BugInstance> inBoth = getSharedInstances(result, origCollection);
			
			// Remove the shared instances from the result
			removeBugInstances(result, inBoth);
			
			return newCollection;
		}
	};
	
	/**
	 * Get the warnings which were <em>retained</em>,
	 * meaning that they occur in both the original and new BugCollections.
	 * The BugInstances returned are from the new BugCollection.
	 */
	public static final SetOperation RETAINED_WARNINGS = new SetOperation(){
		public SortedBugCollection perform(Set<BugInstance> result,
				SortedBugCollection origCollection, SortedBugCollection newCollection) {
			result.addAll(newCollection.getCollection());
			if (DEBUG) System.out.println(result.size() + " instances initially");
			
			// Get shared instances
			List<BugInstance> inBoth = getSharedInstances(result, origCollection);
			
			// Replace instances with only those shared
			replaceBugInstances(result, inBoth);
			
			if (DEBUG) System.out.println(result.size() + " after retaining new instances");
			return newCollection;
		}
	};
	
	/**
	 * Get the warnings which were <em>removed</em>,
	 * meaning that they occur in the original BugCollection but not in
	 * the new BugCollection.
	 * The BugInstances returned are from the original BugCollection.
	 */
	public static final SetOperation REMOVED_WARNINGS = new SetOperation(){
		public SortedBugCollection perform(Set<BugInstance> result,
				SortedBugCollection origCollection, SortedBugCollection newCollection) {
			result.addAll(origCollection.getCollection());
			
			// Get shared instances
			List<BugInstance> inBoth = getSharedInstances(result, newCollection);
			
			// Remove shared instances
			removeBugInstances(result, inBoth);
			
			return origCollection;
		}
	};
	
	private SortedBugCollection origCollection, newCollection;
	private SortedBugCollection originator;
	private Comparator<BugInstance> comparator;
	
	/**
	 * Contructor.
	 * 
	 * @param origCollection the original BugCollection
	 * @param newCollection  the new BugCollection
	 */
	public BugHistory(SortedBugCollection origCollection, SortedBugCollection newCollection) {
		this.origCollection = origCollection;
		this.newCollection = newCollection;
	}
	
	/**
	 * Get the Comparator used to compare BugInstances from different BugCollections.
	 */
	public Comparator<BugInstance> getComparator() {
		return comparator;
	}
	
	/**
	 * @param comparator The comparator to set.
	 */
	public void setComparator(Comparator<BugInstance> comparator) {
		this.comparator = comparator;
	}

	/**
	 * Perform a SetOperation.
	 * 
	 * @param operation the SetOperation
	 * @return the BugCollection resulting from performing the SetOperation
	 */
	public SortedBugCollection performSetOperation(SetOperation operation) {
		// Create a result set which uses the version-insensitive/fuzzy bug comparator.
		// This will help figure out which bug instances are the "same"
		// between versions.
		TreeSet<BugInstance> result = new TreeSet<BugInstance>(getComparator());
		
		// Perform the operation, keeping track of which input BugCollection
		// should be cloned for metadata.
		originator = operation.perform(result, origCollection, newCollection);
		
		// Clone the actual BugInstances selected by the set operation.
		Collection<BugInstance> selected = new LinkedList<BugInstance>();
		BugCollection.cloneAll(selected, result);
		
		// Duplicate the collection from which the results came,
		// in order to copy all metadata, such as analysis errors,
		// class/method hashes, etc.
		SortedBugCollection resultCollection = originator.duplicate();
		
		// Replace with just the cloned instances of the subset selected by the set operation.
		resultCollection.clearBugInstances();
		resultCollection.addAll(selected);
		
		return resultCollection;
	}
	
	/**
	 * @return Returns the originator.
	 */
	public SortedBugCollection getOriginator() {
		return originator;
	}

	/**
	 * Get instances shared between given Set and BugCollection.
	 * The Set is queried for membership, because it has a special Comparator
	 * which can match BugInstances from different versions.
	 * 
	 * @param result     the Set
	 * @param collection the BugCollection
	 * @return List of shared instances
	 */
	private static List<BugInstance> getSharedInstances(Set<BugInstance> result, SortedBugCollection collection) {
		List<BugInstance> inBoth = new LinkedList<BugInstance>();
		for (Iterator<BugInstance> i = collection.iterator(); i.hasNext();) {
			BugInstance origBugInstance = i.next();
			if (result.contains(origBugInstance)) {
				inBoth.add(origBugInstance);
			}
		}
		return inBoth;
	}

	/**
	 * Replace all of the BugInstances in given Set with the given Collection.
	 * 
	 * @param dest   the Set to replace the instances of
	 * @param source the Collection containing the instances to put in the Set
	 */
	private static void replaceBugInstances(Set<BugInstance> dest, Collection<BugInstance> source) {
		dest.clear();
		dest.addAll(source);
	}

	/**
	 * Remove bug instances from Set.
	 * 
	 * @param result   the Set
	 * @param toRemove Collection of BugInstances to remove
	 */
	private static void removeBugInstances(Set<BugInstance> result, Collection<BugInstance> toRemove) {
		for (Iterator<BugInstance> i = toRemove.iterator(); i.hasNext(); ) {
			result.remove(i.next());
		}
	}
	
	private static final int VERSION_INSENSITIVE_COMPARATOR = 0;
	private static final int FUZZY_COMPARATOR = 1;
	private static final int SLOPPY_COMPARATOR = 2;
	
	private static class BugHistoryCommandLine extends CommandLine {
		private int comparatorType = VERSION_INSENSITIVE_COMPARATOR;
		private boolean count;
		private SetOperation setOp;
		
		public BugHistoryCommandLine() {
			addSwitch("-fuzzy", "use fuzzy warning matching");
			addSwitch("-sloppy", "use sloppy warning matching");
			addSwitch("-added", "compute added warnings");
			addSwitch("-new", "same as \"-added\" switch");
			addSwitch("-removed", "compute removed warnings");
			addSwitch("-fixed", "same as \"-removed\" switch");
			addSwitch("-retained", "compute retained warnings");
			addSwitch("-count", "just print warning count");
		}
		
		 /* (non-Javadoc)
		 * @see edu.umd.cs.findbugs.config.CommandLine#handleOption(java.lang.String, java.lang.String)
		 */
		//@Override
		protected void handleOption(String option, String optionExtraPart) throws IOException {
			if (option.equals("-fuzzy")) {
				comparatorType = FUZZY_COMPARATOR;
			} else if (option.equals("-sloppy")) {
				comparatorType = SLOPPY_COMPARATOR;
			} else if (option.equals("-added") || option.equals("-new")) {
				setOp = ADDED_WARNINGS;
			} else if (option.equals("-removed") || option.equals("-fixed")) {
				setOp = REMOVED_WARNINGS;
			} else if (option.equals("-retained")) {
				setOp = RETAINED_WARNINGS;
			} else if (option.equals("-count")) {
				count = true;
			} else {
				throw new IllegalArgumentException("Unknown option: " + option);
			}
		}
		
		/* (non-Javadoc)
		 * @see edu.umd.cs.findbugs.config.CommandLine#handleOptionWithArgument(java.lang.String, java.lang.String)
		 */
		//@Override
		protected void handleOptionWithArgument(String option, String argument) throws IOException {
			throw new IllegalArgumentException("Unknown option: " + option);
		}
		
		/**
		 * @return Returns the comparatorType.
		 */
		public int getComparatorType() {
			return comparatorType;
		}
		
		/**
		 * @return true if we should just output the delta
		 */
		public boolean isCount() {
			return count;
		}
		
		/**
		 * @return Returns the set operation to apply.
		 */
		public SetOperation getSetOp() {
			return setOp;
		}
	}

	public static void main(String[] argv) throws Exception {
		
		BugHistoryCommandLine commandLine = new BugHistoryCommandLine();
		int argCount = commandLine.parse(argv);
		if (argv.length - argCount != 2) {
			printUsage();
		}
		
		if (commandLine.getSetOp() == null) {
			System.err.println("No set operation specified");
			System.exit(1);
		}

		Project origProject = new Project();
		SortedBugCollection origCollection = readCollection(argv[argCount++], origProject);
		Project newProject = new Project();
		SortedBugCollection newCollection = readCollection(argv[argCount++], newProject);
		
		BugHistory bugHistory = new BugHistory(origCollection, newCollection);

		// Create comparator
		Comparator<BugInstance> comparator;
		switch (commandLine.getComparatorType()) {
		case VERSION_INSENSITIVE_COMPARATOR:
			comparator = VersionInsensitiveBugComparator.instance();
			break;
		case FUZZY_COMPARATOR:
			FuzzyBugComparator fuzzy = new FuzzyBugComparator();
			fuzzy.registerBugCollection(origCollection);
			fuzzy.registerBugCollection(newCollection);
			comparator = fuzzy;
			break;
		case SLOPPY_COMPARATOR:
			comparator = new SloppyBugComparator();
			break;
		default:
			throw new IllegalStateException();
		}
		bugHistory.setComparator(comparator);
		
		SortedBugCollection result = bugHistory.performSetOperation(commandLine.getSetOp());
		
		if (commandLine.isCount()) {
			System.out.println(result.getCollection().size());
		} else {
			result.writeXML(
					System.out, bugHistory.getOriginator() == origCollection ? origProject : newProject);
		}
	}

	/**
	 * Print usage and exit.
	 */
	private static void printUsage() {
		System.err.println("Usage: " + BugHistory.class.getName() +
		        " [options] <operation> <old results> <new results>");
		new BugHistoryCommandLine().printUsage(System.err);
		System.exit(1);
	}
	
	private static SortedBugCollection readCollection(String fileName, Project project)
			throws IOException, DocumentException {
		SortedBugCollection result = new SortedBugCollection();
		result.readXML(fileName, project);
		return result;
	}
}

// vim:ts=4

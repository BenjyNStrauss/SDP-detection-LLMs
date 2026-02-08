package patternworks.pattern.aiClassify;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Main for parsing LLM Output
 * @author Benjamin Strauss
 * 
 */

public class LLMOutputParserStandalone {
	private static final boolean ALLOW_HYBRID = false;
	
	private static final double CUTOFF = 0.9;
	
	private static final String HEADER = "Project,Filename,Anonymized,Jaccard,Pairwise Jaccard,Avg Distance,Avg Max Certainty,Max Certainty Variance";
	private static final String HEADER2 = "Project,Filename,Anonymized,ChatGPT,Copilot,Gemini,Claude,Perplexity";
	private static final String HEADER3 = "Project,Filename,anon-improved,anon-improved-pairwise";
	private static final String BASE_DIR = "research/llm-pattern-detection";
	private static final String ANALYSIS = BASE_DIR+"/@analysis";
	
	private static final File OUTFILE = new File(BASE_DIR+"/analysis.csv");
	private static final File ACCFILE = new File(BASE_DIR+"/accuracy.csv");
	private static final File COMP_FILE = new File(BASE_DIR+"/jaccard-compare.csv");
	
	private static final HashSet<AI_Classification> ANSWERS = new HashSet<>();
	private static final HashSet<FileSummary> COMPARISONS = new HashSet<>();
	
	private static final double[] AMCP_THRESHOLDS = { 0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9, 0.95 };
	
	private static enum LLM {
		CHATGPT, COPILOT, GEMINI, CLAUDE, PERPLEXITY;
		
		public static LLM parse(String arg) {
			arg = arg.trim().toLowerCase();
			switch(arg) {
			case "chatgpt":			return CHATGPT;
			case "copilot":			return COPILOT;
			case "gemini":			return GEMINI;
			case "claude":			return CLAUDE;
			case "perplexly":
			case "perplexity":		return PERPLEXITY;
			default:				throw new RuntimeException(arg);
			}
		}
	}
	
	public static class Pair<X, Y> implements Serializable {
		private static final long serialVersionUID = 1L;
		public X x;
		public Y y;
		
		/**
		 * Constructs a new (empty) Pair
		 */
		public Pair() { }
		
		/**
		 * Constructs a new Pair with only 1 value
		 * @param x
		 */
		public Pair(final X x) { this.x = x; }
		
		/**
		 * Constructs a new Pair with 2 values
		 * @param x
		 * @param y
		 */
		public Pair(final X x, final Y y) {
			this.x = x;
			this.y = y;
		}
		
		@Override
		public int hashCode() {
			if(x == null && y == null) { return 0; }
			if(x == null || y == null) { return (y == null) ? x.hashCode() : y.hashCode(); }
			return x.hashCode() ^ y.hashCode();
		}
		
		@Override
		public boolean equals(final Object other) {
			if(!(other instanceof Pair)) {
				return false;
			} else {
				final Pair<?,?> otherPair = (Pair<?,?>) other;
				
				if(!perfectEquals(x, otherPair.x)) { return false; }
				if(!perfectEquals(y, otherPair.y)) { return false; }
				return true;
			}
		}
		
		@Override
		public String toString() { return x + "," + y; }
	}
	
	public static class AssistFile extends File implements Iterable<AssistFile> {
		private static final long serialVersionUID = 1L;
		
		/**
		 * Constructs a new AssistFile
		 * @param pathname
		 */
		public AssistFile(final String pathname) { super(pathname); }
		
		/**
		 * Constructs a new AssistFile
		 * @param parent
		 * @param child
		 */
		public AssistFile(final String parent, final String child) { super(parent, child); }
		
		/**
		 * Constructs a new AssistFile
		 * @param parent
		 * @param child
		 */
		public AssistFile(final File parent, final String child) { super(parent, child); }
		
		/**
		 * Constructs a new AssistFile
		 * @param uri
		 */
		public AssistFile(final URI uri) { super(uri); }

		@Override
		public Iterator<AssistFile> iterator() { return new FileTreeIterator(this); }
	}
	
	public static class FileTreeIterator implements Iterator<AssistFile> {
		//base directory
		private final File base_dir;
		//
		private final String[] contents;
		//
		public int arrayPos = 0;
		//iterates over the sub-directory
		private FileTreeIterator sub_iter = null;
		
		/**
		 * Constructs a new FileTreeIterator
		 * @param base_dir: directory to be iterated over
		 */
		public FileTreeIterator(final String base_dir) { this(new File(base_dir), false); }
		
		/**
		 * Constructs a new FileTreeIterator
		 * @param base_dir: directory to be iterated over
		 * @param reverse: if true, start the iterator at the end of the file tree
		 */
		public FileTreeIterator(final String base_dir, final boolean reverse) {
			this(new File(base_dir), reverse);
		}
		
		/**
		 * Constructs a new FileTreeIterator
		 * @param base_dir: directory to be iterated over
		 */
		public FileTreeIterator(final File base_dir) {
			this(base_dir, false);
		}
		
		/**
		 * Constructs a new FileTreeIterator
		 * @param base_dir: directory to be iterated over
		 * @param reverse: if true, start the iterator at the end of the file tree
		 */
		public FileTreeIterator(final File base_dir, final boolean reverse) {
			Objects.requireNonNull(base_dir);
			
			if(!base_dir.exists()) {
				throw new RuntimeException("File not found: "+base_dir);
			}
			
			//System.out.println(base_dir);
			this.base_dir = base_dir;
			contents = base_dir.list();
			
			//Objects.requireNonNull(contents);
			
			arrayPos = (reverse) ? contents.length : 0;
		}
		
		/** @return base directory being iterated over */
		public final File base() { return base_dir; }

		@Override public boolean hasNext() {
			//if we're iterating through a sub-directory
			if(sub_iter != null && sub_iter.hasNext()) {
				return true;
			}
			
			//skip empty directories
			while(arrayPos < contents.length && isEmptyDir(makeFileObj())) {
				//skipping is the only time to advance the arrayPos in hasNext()
				++arrayPos;
			}
			
			return arrayPos < contents.length;
		}
		
		/** @return true if the iterator has a previous value */
		public boolean hasPrevious() {
			//if we're iterating through a sub-directory
			if(sub_iter != null && sub_iter.hasPrevious()) {
				return true;
			}
			
			return arrayPos > 0;
		}

		@Override public AssistFile next() {
			if(contents.length == 0) { 
				return null;
			} else if(arrayPos > contents.length) {
				return null;
			} else if(arrayPos == contents.length && sub_iter == null) {
				return null;
			}
			
			AssistFile nextFile;
			
			if(sub_iter != null) {
				if(sub_iter.hasNext()) {
					nextFile = sub_iter.next();
					if(nextFile != null) { return nextFile; }
				} else {
					sub_iter = null;
				}
			}
			
			nextFile = makeFileObj();
			
			++arrayPos;
			
			if(nextFile.isDirectory()) {
				sub_iter = new FileTreeIterator(nextFile);
				return sub_iter.next();
			}
			
			Objects.requireNonNull(nextFile);
			return nextFile;
		}
		
		/** @return previous value */
		public AssistFile previous() {
			if(contents.length == 0) { return null; }
			AssistFile prevFile;
			
			if(sub_iter != null) {
				if(sub_iter.hasPrevious()) {
					prevFile = sub_iter.previous();
					if(prevFile != null) { return prevFile; }
				} else {
					sub_iter = null;
				}
			}
			
			arrayPos--;
			prevFile = makeFileObj();
			
			if(prevFile.isDirectory()) {
				sub_iter = new FileTreeIterator(prevFile, true);
				return sub_iter.previous();
			}
			
			return prevFile;
		}
		
		/** @return How deep we are in the tree */
		public int currentDepth() { return (sub_iter == null) ? 1 : sub_iter.currentDepth()+1; }
		
		/**
		 * 
		 * @param name
		 * @return
		 */
		public boolean mkdir(final String name) {
			if(sub_iter != null) { return sub_iter.mkdir(name); }
			return new File(base_dir.getPath() + "/" + name).mkdir();
		}
		
		/**
		 * 
		 * @param name
		 * @return
		 */
		public boolean createNewFile(final String name) {
			if(sub_iter != null) { return sub_iter.createNewFile(name); }
			try {
				return new File(base_dir.getPath() + "/" + name).createNewFile();
			} catch (final IOException e) {
				return false;
			}
		}
		
		@Override
		public void remove() { makeFileObj().delete(); }
		
		/**
		 * Makes a AssistFile Object
		 * @return: AssistFile
		 */
		private AssistFile makeFileObj() {
			final AssistFile af = new AssistFile(base_dir.getPath() + "/" + contents[arrayPos]);
			Objects.requireNonNull(af, "Error, null file: "+base_dir.getPath() + "/" + contents[arrayPos]);
			return af;
		}
		
		/**
		 * 
		 * @param file
		 * @return true if the directory is empty
		 */
		private static boolean isEmptyDir(final File file) {
			if(!file.isDirectory()) { return false; }
			return file.list().length == 0;
		}
	}
	
	public static class QuantizableFrame<E> implements Comparable<QuantizableFrame<?>>, Serializable {
		private static final long serialVersionUID = 1L;
		
		public final E data;
		public double value;
		
		/**
		 * Constructs a new QuantizableFrame
		 * @param data
		 * @param value
		 */
		public QuantizableFrame(final E data, final double value) {
			Objects.requireNonNull(data, "QuantizableFrame cannot accept null values.");
			this.data = data;
			this.value = value;
		}
		
		public double quantize() { return value; }
		
		@Override
		public int hashCode() { return data.hashCode(); }
		
		@Override
		public boolean equals(final Object other) {
			if(other instanceof QuantizableFrame) {
				return data.equals(((QuantizableFrame<?>) other).data);
			} else {
				return data.equals(other);
			}
		}
		
		@Override
		public String toString() { return data.toString(); }
		
		public int compareTo(final QuantizableFrame<?> other) {
			if(quantize() == other.quantize()) { 
				return 0;
			} else {
				return (quantize() < other.quantize()) ? -1 : 1; 
			}
		}
	}
	
	public static class InstanceCounter<E> {
		protected final Hashtable<E, Long> counter;
		
		/**
		 * Constructs a new InstanceCounter
		 */
		public InstanceCounter() { counter = new Hashtable<E, Long>(); }

		public void increment(final E instance) { increment(instance, 1L); }
		
		public void increment(final E instance, final Long amount) {
			Objects.requireNonNull(instance, "Hashtables do not allow null keys.");
			if(counter.containsKey(instance)) {
				counter.put(instance, counter.get(instance)+amount);
			} else {
				counter.put(instance, amount);
			}
		}
		
		public void decrement(final E instance) { increment(instance, 1L); }
		
		public void decrement(final E instance, final Long amount) {
			if(counter.containsKey(instance)) {
				counter.put(instance, counter.get(instance)-amount);
			} else {
				counter.put(instance, -amount);
			}
		}
		
		public HashSet<E> mode() {
			final var modes = new HashSet<E>();
			
			long max_value = 0;
			
			for(final E key: counter.keySet()) {
				final long value = counter.get(key);
				
				if(value > max_value) {
					max_value = value;
					modes.clear();
					modes.add(key);
				} else if(value == max_value) {
					modes.add(key);
				}
			}
			
			return modes;
		}
	}
	
	public static class SetSimilarity<E> {
		
		private final HashSet<E> disregardValues;
		
		/**
		 * Construct a new SetSimilarity
		 */
		public SetSimilarity() { 
			disregardValues = new HashSet<E>();
		}
		
		/**
		 * Construct a new SetSimilarity
		 * @param disregard
		 */
		@SuppressWarnings("unchecked")
		public SetSimilarity(final E... disregard) { 
			disregardValues = new HashSet<E>();
			for(final E e: disregard) {
				disregardValues.add(e);
			}
		}
		
		/**
		 * Perform unweighted Jaccard similarity
		 * @param sets
		 * @return
		 */
		public double jaccard(final Iterable<?> sets) {
			Objects.requireNonNull(sets);
			final ArrayList<Set<E>> structure = assembleInput(sets);
			
			if(structure == null) {
				System.err.println("A processing error occurred.  Please check data structure integrity.");
				return Double.NaN;
			}
			
			final HashSet<E> union = new HashSet<E>();
			for(final Set<E> set: structure) {
				union.addAll(set);
			}
			
			final HashSet<E> intersection = new HashSet<E>();
			intersection.addAll(union);
			for(final Set<E> set: structure) {
				intersection.retainAll(set);
			}
			
			final double u_size = union.size();
			final double i_size = intersection.size();
			
			return i_size/u_size;
		}
		
		public double jaccard(final Iterable<?> sets, final Function<E, Double> weightFunction) {
			return jaccard(sets, weightFunction, 0.0);
		}
		
		/**
		 * Perform weighted Jaccard similarity
		 * @param sets
		 * @param weightFunction
		 * @param cutoff
		 * @return
		 */
		public double jaccard(final Iterable<?> sets, final Function<E, Double> weightFunction, final double cutoff) {
			Objects.requireNonNull(sets);
			Objects.requireNonNull(weightFunction);
			final ArrayList<Set<E>> structure = assembleInput(sets);
			
			//since 'structure' is cloned, we can modify it without affecting the original
			applyCutoff(structure, weightFunction, cutoff);
			
			if(structure == null) {
				System.err.println("A processing error occurred.  Please check data structure integrity.");
				return Double.NaN;
			}
			
			//list of hashes
			final ArrayList<Hashtable<E, Double>> weights = new ArrayList<Hashtable<E, Double>>();
			for(int index = 0; index < structure.size(); ++index) {
				weights.add(new Hashtable<E, Double>());
				for (final E element: structure.get(index)) {
					weights.get(index).put(element, weightFunction.apply(element));
				}
			}
			
			final HashSet<E> union = new HashSet<E>();
			for(int index = 0; index < structure.size(); ++index) {
				 union.addAll(weights.get(index).keySet());
			}
	        
	        double numerator = 0.0;
	        double denominator = 0.0;

	        for(final E element : union) {
	        	double max = weights.get(0).getOrDefault(element, 0.0);
	        	double min = weights.get(0).getOrDefault(element, 0.0);
	        	
	        	for(int index = 1; index < structure.size(); ++index) {
	        		max = Math.max(max, weights.get(index).getOrDefault(element, 0.0));
	        		min = Math.min(max, weights.get(index).getOrDefault(element, 0.0));
	        	}
	            
	            numerator += min;
	            denominator += max;
	        }
			
			return denominator == 0.0 ? Double.NaN : numerator / denominator;
		}
		
		public double pairwiseJaccard(final Iterable<?> sets, final Function<E, Double> weightFunction) {
			return pairwiseJaccard(sets, weightFunction, 0.0);
		}
		
		/**
		 * Perform average pairwise weighted Jaccard similarity
		 * @param sets
		 * @param weightFunction
		 * @return
		 */
		public double pairwiseJaccard(final Iterable<?> sets, final Function<E, Double> weightFunction,
				final double cutoff) {
			final ArrayList<Set<E>> structure = assembleInput(sets);
			
			applyCutoff(structure, weightFunction, cutoff);
			
			final ArrayList<Pair<Set<E>, Set<E>>> structure2 = new ArrayList<>();
			for(int ii = 0; ii < structure.size(); ++ii) {
				for(int jj = ii+1; jj < structure.size(); ++jj) {
					structure2.add(new Pair<Set<E>, Set<E>>(structure.get(ii), structure.get(jj)));
				}
			}
			
			double total = 0;
			for(final Pair<Set<E>, Set<E>> pair: structure2) {
				total += jaccard(pair.x, pair.y, weightFunction);
				//qp("@total="+total);
			}
			
			return total/structure2.size();
		}
		
		/**
		 * 
		 * @param set1
		 * @param set2
		 * @param weightFunction
		 * @return
		 */
		public double jaccard(final Iterable<E> set1, final Iterable<E> set2, final Function<E, Double> weightFunction) {
			Objects.requireNonNull(set1);
			Objects.requireNonNull(set2);
			Objects.requireNonNull(weightFunction);
			
			final Hashtable<E, Double> weights1 = new Hashtable<E, Double>();
			for (final E element: set1) {
				weights1.put(element, weightFunction.apply(element));
			}
			
			final Hashtable<E, Double> weights2 = new Hashtable<E, Double>();
			for (final E element: set2) {
				weights2.put(element, weightFunction.apply(element));
			}
			
			final HashSet<E> union = new HashSet<E>();
			union.addAll(weights1.keySet());
			union.addAll(weights2.keySet());
	        
	        double numerator = 0.0;
	        double denominator = 0.0;
	        
	        for (final E key : union) {
	            final double w1 = weights1.getOrDefault(key, 0.0);
	            final  double w2 = weights2.getOrDefault(key, 0.0);
	            
	            numerator += Math.min(w1, w2);
	            denominator += Math.max(w1, w2);
	        }
	        
			return denominator == 0.0 ? Double.NaN : numerator / denominator;
		}
		
		/**
		 * 
		 * @param sets
		 * @return
		 */
		@SuppressWarnings("unchecked")
		private ArrayList<Set<E>> assembleInput(final Iterable<?> sets) {
			final ArrayList<Set<E>> structure = new ArrayList<Set<E>>();
			
			//Ensure the data structure is properly set up
			for(final Object collection: sets) {
				final HashSet<E> set = new HashSet<E>();
				
				if(collection instanceof Iterable<?>) {
					for(final Object element: (Iterable<?>) collection) {
						try {
							set.add((E) element);
						} catch (final ClassCastException CCE) {
							return null;
						}
					}
				}
				
				structure.add(set);
			}
			
			for(final E element: disregardValues) {
				for(final Set<E> set: structure) {
					set.remove(element);
				}
			}
			
			return structure;
		}
		
		private void applyCutoff(final ArrayList<Set<E>> structure, final Function<E, Double> weightFunction,
				final double cutoff) {
			//since 'structure' is cloned, we can modify it without affecting the original
			for(final Set<E> set: structure) {
				final Set<E> remove = new HashSet<E>();
				
				for(final E element: set) {
					if(weightFunction.apply(element) < cutoff) {
						remove.add(element);
					}
				}
				
				set.removeAll(remove);
			}
		}
		
		@Override
		public String toString() { return "assist.stats: Set Similarity Module"; }
	}
	
	public static class QuantifiedPattern implements Serializable {
		private static final long serialVersionUID = 1L;
		public final PatternType pattern;
		public final double certainty;
		public final double correctness;
		
		/**
		 * 
		 * @param pattern
		 * @param certainty
		 * @param correctness
		 */
		public QuantifiedPattern(final PatternType pattern, final double certainty, final double correctness) {
			this.pattern = pattern;
			this.certainty = certainty;
			this.correctness = correctness;
		}

		/**
		 * 
		 * @param arg
		 * @return
		 */
		public static QuantifiedPattern parse(String arg) {
			arg = arg.trim().substring(1);
			
			final String[] fields = arg.split("\\|");
			for(int index = 0; index < fields.length; ++index) {
				fields[index] = fields[index].trim();
			}	
			
			boolean chatgpt_adjusted = false;
			
			//standardize chatgpt responses
			if(isInt(fields[0])) {
				for(int index = 0; index < fields.length-1; ++index) {
					fields[index] = fields[index+1];
				}
				chatgpt_adjusted = true;
			}
			
			if(fields.length > 3 && containsPercentAtFieldNo(arg, 2) && containsPercentAtFieldNo(arg, 3) && !chatgpt_adjusted) {
				for(int index = 1; index < fields.length-1; ++index) {
					fields[index] = fields[index+1];
				}
			}
			
			//qp(arg);
			final PatternType pattern = PatternType.parse(fields[0]);
			final double confidence   = Double.parseDouble(fields[1].replaceAll("%", ""));
			final double correctness  = Double.parseDouble(fields[2].replaceAll("%", ""));
			
			return new QuantifiedPattern(pattern, confidence/100, correctness/100);
		}
		
		/**
		 * 
		 * @param other
		 * @param isPercent
		 * @return
		 */
		public double similarity(final QuantifiedPattern other, final boolean isPercent) {
			//treat certainty as a percent if > 1
			final double my_conf    = isPercent ? certainty       / 100 : certainty;
			final double other_conf = isPercent ? other.certainty / 100 : other.certainty;
			
			if(pattern == other.pattern) {
				final double conf_agree = 1-Math.abs(my_conf - other_conf);
				return conf_agree;
			} else {
				return 0;
			}
		}
		
		/**
		 * 
		 * @param other
		 * @param isPercent
		 * @return
		 */
		public double similarityWithCorrectness(final QuantifiedPattern other, final boolean isPercent) {
			//treat certainty as a percent if > 1
			final double my_conf    = isPercent ? certainty       / 100 : certainty;
			final double other_conf = isPercent ? other.certainty / 100 : other.certainty;
			
			final double my_corr    = isPercent ? correctness       / 100 : correctness;
			final double other_corr = isPercent ? other.correctness / 100 : other.correctness;
			
			if(pattern == other.pattern) {
				final double conf_agree = 1-Math.abs(my_conf - other_conf);
				final double correct_agree = 1-Math.abs(my_corr - other_corr);
				return conf_agree * Math.sqrt(correct_agree);
			} else {
				return 0;
			}
		}
		
		@Override
		public int hashCode() { return pattern.ordinal(); }
		
		@Override
		public boolean equals(final Object other) { 
			if(other instanceof QuantifiedPattern) {
				final QuantifiedPattern otherQP = (QuantifiedPattern) other;
				//if(pattern != otherQP.pattern) { return false; }
				//if(certainty != otherQP.certainty) { return false; }
				//if(correctness != otherQP.correctness) { return false; }
				return pattern == otherQP.pattern;
			} else {
				return false;
			}
		}
		
		@Override
		public String toString() { return pattern+"["+certainty+","+correctness+"]"; }
		
		/**
		 * 
		 * @param percent
		 * @return
		 */
		public static QuantifiedPattern makeDummy(final boolean percent) {
			return new QuantifiedPattern(PatternType.NONE, percent ? 100 : 1, percent ? 100 : 1);
		}
		
		/**
		 * 
		 * @param line
		 * @param index
		 * @return
		 */
		public static boolean containsPercentAtFieldNo(final String line, final int index) {
			//qp(line.substring(1).split("\\|"));
			return isInt(line.substring(1).split("\\|")[index].replaceAll("%", "").trim());
		}
	}
	
	public static class AI_Classification implements Iterable<QuantifiedPattern>, Serializable {
		private static final long serialVersionUID = 1L;
		
		public  final String project;
		public  final String filename;
		public  final LLM ai;
		public  final boolean anonymized;
		private final HashSet<QuantifiedPattern> patterns;
		
		/**
		 * Constructs a new AI_Classification
		 * @param project
		 * @param filename
		 * @param ai
		 * @param patterns
		 */
		public AI_Classification(final String project, final String filename, final LLM ai, final QuantifiedPattern... patterns) {
			this(project, filename, ai, false, patterns);
		}
		
		/**
		 * Constructs a new AI_Classification
		 * @param project
		 * @param filename
		 * @param ai
		 * @param anonymized
		 * @param patterns
		 */
		public AI_Classification(final String project, final String filename, final LLM ai, final boolean anonymized, final QuantifiedPattern... patterns) {
			Objects.requireNonNull(ai);
			Objects.requireNonNull(project);
			Objects.requireNonNull(filename);
			this.patterns = new HashSet<QuantifiedPattern>();
			for(final QuantifiedPattern pattern: patterns) {
				this.patterns.add(pattern);
			}
			
			this.anonymized = anonymized;
			this.ai = ai;
			this.project = project;
			this.filename = filename;
		}
		
		@Override
		public Iterator<QuantifiedPattern> iterator() {
			return patterns.iterator();
		}
		
		/**
		 * 
		 * @param pattern
		 * @return
		 */
		public boolean add(final QuantifiedPattern pattern) {
			for(final QuantifiedPattern qp: patterns) {
				if(qp.pattern == pattern.pattern) {
					if(qp.certainty > pattern.certainty) {
						return false;
					} else {
						patterns.remove(qp);
						return patterns.add(pattern);
					}
				}
			}
			return patterns.add(pattern);
		}
		
		public double similarity(final AI_Classification other) {
			//First, create new sets of equal length
			final ArrayList<QuantifiedPattern> sdps1 = new ArrayList<QuantifiedPattern>();
			for(final QuantifiedPattern qp: patterns) {
				sdps1.add(qp);
			}
			final ArrayList<QuantifiedPattern> sdps2 = new ArrayList<QuantifiedPattern>();
			for(final QuantifiedPattern qp: other.patterns) {
				sdps2.add(qp);
			}
			
			//Do the padding
			final int compare_len = Math.max(patterns.size(), other.patterns.size());
			while(sdps1.size() < compare_len) {
				sdps1.add(QuantifiedPattern.makeDummy(true));
			}
			while(sdps2.size() < compare_len) {
				sdps2.add(QuantifiedPattern.makeDummy(true));
			}
			
			//data structure to hold the best matches
			final Hashtable<QuantifiedPattern, Double> scores1 = new Hashtable<>();
			final Hashtable<QuantifiedPattern, Double> scores2 = new Hashtable<>();
			
			for(final QuantifiedPattern qp: sdps1) {
				scores1.put(qp, 0.0);
				for(final QuantifiedPattern oqp: sdps2) {
					final double sim_score = qp.similarity(oqp, true);
					if(sim_score > scores1.get(qp)) {
						scores1.put(qp, sim_score);
					}
				}
			}
			
			for(final QuantifiedPattern qp: sdps2) {
				scores2.put(qp, 0.0);
				for(final QuantifiedPattern oqp: sdps1) {
					final double sim_score = qp.similarity(oqp, true);
					if(sim_score > scores2.get(qp)) {
						scores2.put(qp, sim_score);
					}
				}
			}
			
			double average = 0;
			for(final QuantifiedPattern qp: scores1.keySet()) {
				average += scores1.get(qp);
			}
			for(final QuantifiedPattern qp: scores2.keySet()) {
				average += scores2.get(qp);
			}
			
			average /= (compare_len*2);
			return average;
		}
		
		public int size() { return patterns.size(); }

		public boolean contains(final Object e) { return patterns.contains(e); }
		
		public QuantifiedPattern[] toArray() { 
			final QuantifiedPattern[] array = new QuantifiedPattern[patterns.size()];
			patterns.toArray(array);
			return array;
		}
		
		@Override
		public boolean equals(final Object other) {
			if(other instanceof AI_Classification) {
				final AI_Classification aic = (AI_Classification) other;
				if(!project.equals(aic.project)) { return false; }
				if(!filename.equals(aic.filename)) { return false; }
				if(!ai.equals(aic.ai)) { return false; }
				if(anonymized != aic.anonymized) { return false; }
				if(!perfectEquals(patterns, aic.patterns)) { return false; }
				return true;
			} else {
				return false;
			}
		}
		
		@Override
		public String toString() {
			return "Classification of "+project+"::"+filename+" by "+ai;
		}
	}
	
	public static class FileSummary extends LinkedHashSet<AI_Classification> implements Comparable<FileSummary> {
		private static final long serialVersionUID = 1L;
		protected String label;
		
		public final String  project;
		public final String  filename;
		public final boolean anonymized;
		
		private double avgDistance			= Double.NaN;
		private double jaccard				= Double.NaN;
		private double pairwiseJaccard		= Double.NaN;
		private double avgMaxCertainty		= Double.NaN;
		private double maxCertaintyVariance = Double.NaN;
		
		private final HashSet<PatternType> fromName;
		
		/**
		 * 
		 * @param aic
		 */
		public FileSummary(AI_Classification aic) {
			this(aic.project, aic.filename, aic.anonymized);
			add(aic);
		}
		
		/**
		 * 
		 * @param project
		 * @param filename
		 * @param patterns
		 */
		public FileSummary(final String project, final String filename, final AI_Classification... patterns) {
			this(project, filename, false, patterns);
		}
		
		/**
		 * 
		 * @param project
		 * @param filename
		 * @param anonymized
		 * @param patterns
		 */
		public FileSummary(final String project, final String filename, final boolean anonymized, final AI_Classification... patterns) {
			Objects.requireNonNull(project);
			Objects.requireNonNull(filename);
			label = filename;
			this.anonymized = anonymized;
			this.project = project;
			this.filename = filename;
			fromName = new HashSet<PatternType>();
			processName(filename);
		}

		@Override
		public boolean add(final AI_Classification element) {
			Objects.requireNonNull(element);
			if(!accepts(element)) { return false; }
			resetAll();
			return super.add(element);
		}
		
		@Override
		public int compareTo(final FileSummary other) {
			if(quantize() == other.quantize()) { 
				return 0;
			} else {
				return (quantize() < other.quantize()) ? -1 : 1; 
			}
		}
		
		@Override
		public boolean remove(final Object element) {
			if(contains(element)) {
				resetAll();
			}
			return super.remove(element);
		}
		
		public boolean accepts(final AI_Classification element) {
			if(!project.equals(element.project)) { return false; }
			if(!filename.equals(element.filename)) { return false; }
			if(anonymized != element.anonymized) { return false; }
			
			return true;
		}
		
		public double quantize() { return averageDistance(); }
		
		public void computeAll() { computeAll(0.0); }
		
		public void computeAll(final double jaccardCutoff) {
			
			final ArrayList<Double> distances = new ArrayList<Double>();
			
			for(int ii = 0; ii < size(); ++ii) {
				for(int jj = ii+1; jj < size(); ++jj) {
					//qp("comparing ["+ii+"] to ["+jj+"]");
					distances.add(get(ii).similarity(get(jj)));
				}
			}
			
			if(distances.size() != (size() * (size()-1))/2) {
				System.err.println("Sanity Check = Failed; wrong number of comparisons!");
			}
			
			double sum = 0;
			for(final Double dist: distances) {
				sum += dist;
			}
			
			avgDistance = sum / distances.size();
			
			final SetSimilarity<QuantifiedPattern> comparator = new SetSimilarity<QuantifiedPattern>(
					new QuantifiedPattern(PatternType.NONE,100,100), 
					new QuantifiedPattern(PatternType.NON_PATTERN,100,100));
			jaccard = comparator.jaccard(this, element -> element.correctness, jaccardCutoff);
			pairwiseJaccard = comparator.pairwiseJaccard(this, element -> element.correctness, jaccardCutoff);
			
			avgMaxCertainty = 0;
			final ArrayList<Double> maxCertainties = new ArrayList<Double>();
			final Iterator<AI_Classification> myIter = iterator();
			
			for(int index = 0; index < size(); ++index) {
				double maxCertainty = 0;
				final AI_Classification aic = myIter.next();
				
				for(final QuantifiedPattern qp: aic) {
					if(qp.pattern != PatternType.NONE && qp.pattern != PatternType.NON_PATTERN) {
						maxCertainty = Math.max(maxCertainty, qp.certainty);
					}
				}
				
				if(maxCertainty > 0) {
					maxCertainties.add(maxCertainty);
				}
			}
			
			double min = 100;
			double max = 0;
			avgMaxCertainty = 0;
			for(final double value: maxCertainties) {
				min = Math.min(value, min);
				max = Math.max(value, max);
				avgMaxCertainty += value;
			}
			avgMaxCertainty /= maxCertainties.size();
			maxCertaintyVariance = max - min;
		}
		
		public double jaccard() { return jaccard; }
		public double pairwiseJaccard() { return pairwiseJaccard; }
		public double averageDistance() { return avgDistance; }
		public double avgMaxCertainty() { return avgMaxCertainty; }
		public double maxCertaintyVariance() { return maxCertaintyVariance; }
		
		public AI_Classification get(final int indexOrKey) {
			if(indexOrKey >= size()) { throw new IndexOutOfBoundsException(); }
			final Object[] contents = toArray();
			return (AI_Classification) contents[indexOrKey];
		}
		
		/**
		 * 
		 * @return
		 */
		public Hashtable<LLM, Double> accuracies() {
			final Hashtable<LLM, Double> acc = new Hashtable<>();
			
			for(final AI_Classification aic: this) {
				int matches = 0;
				for(final QuantifiedPattern qpatt: aic) {
					if(fromName.contains(qpatt.pattern)) {
						matches++;
					}
				}
				acc.put(aic.ai, ((double) matches) / ((double) fromName.size()));
			}
			
			return acc;
		}
		
		@Override
		public boolean equals(final Object other) {
			if(!super.equals(other)) { return false; }
			
			if(other instanceof FileSummary) {
				final FileSummary aic = (FileSummary) other;
				if(!project.equals(aic.project)) { return false; }
				if(!filename.equals(aic.filename)) { return false; }
				//if(!ai.equals(aic.ai)) { return false; }
				if(anonymized != aic.anonymized) { return false; }
				return true;
			} else {
				return false;
			}
		}
		
		private void resetAll() {
			avgDistance			 = Double.NaN;
			jaccard				 = Double.NaN;
			pairwiseJaccard 	 = Double.NaN;
			avgMaxCertainty		 = Double.NaN;
			maxCertaintyVariance = Double.NaN;
		}
		
		private void processName(final String filename) {
			final ArrayList<String> name_terms = new ArrayList<String>();
			final StringBuilder termBuilder = new StringBuilder();
			for(final char ch: filename.toCharArray()) {
				if(Character.isUpperCase(ch)) {
					if(termBuilder.length() > 1) {
						name_terms.add(termBuilder.toString());
					}
					termBuilder.setLength(0);
					termBuilder.append(ch);
				} else {
					termBuilder.append(ch);
				}
			}
			if(termBuilder.length() > 1) {
				name_terms.add(termBuilder.toString());
			}
			
			for(final String str: name_terms) {
				PatternType type = PatternType.NONE;
				try {
					type = PatternType.parse(str);
				} catch (final RuntimeException UEVE) { }
				
				if(type != PatternType.NONE && type != PatternType.NON_PATTERN) {
					fromName.add(type);
				}
			}
		}
		
		public String toCSV() {
			return String.join(",", project, filename, ""+anonymized, ""+jaccard, ""+pairwiseJaccard, ""+avgDistance, ""+avgMaxCertainty, ""+maxCertaintyVariance);
		}
		
		@Override
		public String toString() {
			return "Aggregate Classification of "+project+"::"+filename + (((anonymized) ? "-anon" : "")+"(size="+size()+")");
		}
	}
	
	private static final Hashtable<Pair<String, Integer>, String> DEANONYMIZER = new Hashtable<>();
	private static final Hashtable<LLM, Integer> NON_PATTERN_COUNT = new Hashtable<>();
	
	private static final InstanceCounter<String> DEBUG_SET = new InstanceCounter<>();
	
	private static final String JAVA_EXT = ".java";
	
	private static int debug_count = 0;
	
	/**
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		//specialAnalysis();
		final File analysis = new File(ANALYSIS);
		final FileTreeIterator iter = new FileTreeIterator(analysis);
		
		loadDeanonymizer();
		
		/******************************
		 * Read all of the AI Outputs *
		 ******************************/
		while(iter.hasNext()) {
			final AssistFile file = iter.next();
			
			//qerr(file.getPath());
			if(file.getPath().endsWith(".DS_Store")) {
				continue;
			}
			
			final String[] pathParts = file.getPath().split("/");
			if(pathParts[pathParts.length-1].startsWith("*")) {
				continue;
			}
			
			process(file);
		}
		
		int nan_jaccard_2 = 0;
		int nan_jaccard_5 = 0;
		
		double jaccard_2_sum = 0;
		double jaccard_5_sum = 0;
		double jaccard_5_min = 1;
		double jaccard_5_max = 0;
		double jaccard_2_min = 1;
		double jaccard_2_max = 0;
		
		/*******************************
		 * Sort the AI Outputs by file *
		 *******************************/
		for(final AI_Classification aic: ANSWERS ) {
			boolean added = false;
			
			for(final FileSummary fs: COMPARISONS) {
				if(fs.accepts(aic)) {
					fs.add(aic);
					added = true;
					break;
				}
			}
			
			for(final QuantifiedPattern qp: aic) {
				if(qp.pattern == PatternType.NON_PATTERN) {
					
					if(NON_PATTERN_COUNT.containsKey(aic.ai)) {
						NON_PATTERN_COUNT.put(aic.ai, NON_PATTERN_COUNT.get(aic.ai)+1);
					} else {
						NON_PATTERN_COUNT.put(aic.ai, 1);
					}
				}
			}
			
			if(!added) {
				COMPARISONS.add(new FileSummary(aic));
			}
		}
		//specialAnalysis();
		
		writeFileLines(OUTFILE, HEADER);
		for(final FileSummary fs: COMPARISONS) {
			fs.computeAll(CUTOFF);
			appendFileLines(OUTFILE, fs.toCSV());
		}
		
		final StringBuilder lineBuilder = new StringBuilder();
		writeFileLines(ACCFILE, HEADER2);
		for(final FileSummary fs: COMPARISONS) {
			lineBuilder.setLength(0);
			lineBuilder.append(fs.project+","+fs.filename+","+fs.anonymized+",");
			final Hashtable<LLM, Double> accHash = fs.accuracies();
			lineBuilder.append(accHash.get(LLM.CHATGPT)+",");
			lineBuilder.append(accHash.get(LLM.COPILOT)+",");
			lineBuilder.append(accHash.get(LLM.GEMINI)+",");
			lineBuilder.append(accHash.get(LLM.CLAUDE)+",");
			lineBuilder.append(accHash.get(LLM.PERPLEXITY)+",");
			appendFileLines(ACCFILE, lineBuilder.toString());
		}
		
		//find the difference in both jaccards for raw and anonymized - O(n^2)COMP_FILE
		double jac_total_diff = 0;
		double pair_jac_total_diff = 0;
		
		int jac_anon_improve = 0;
		int pair_jac_anon_improve = 0;
		int pair_jac_anon_improve_not_counted = 0;
		
		writeFileLines(COMP_FILE, HEADER3);
		for(final FileSummary fs: COMPARISONS) {
			
			if(!Double.isNaN(fs.jaccard())) {
				jaccard_5_sum += fs.jaccard();
				jaccard_5_min = Math.min(jaccard_5_min, fs.jaccard());
				jaccard_5_max = Math.max(jaccard_5_max, fs.jaccard());
			} else {
				++nan_jaccard_5;
			}
			
			if(!Double.isNaN(fs.pairwiseJaccard())) {
				jaccard_2_sum += fs.pairwiseJaccard();
				jaccard_2_min = Math.min(jaccard_2_min, fs.pairwiseJaccard());
				jaccard_2_max = Math.max(jaccard_2_max, fs.pairwiseJaccard());
			} else {
				++nan_jaccard_2;
			}
			
			if(fs.anonymized) { continue; }
			lineBuilder.setLength(0);
			lineBuilder.append(fs.project+","+fs.filename+",");
			FileSummary counterpart = null;
			
			//qerr("processing: "+fs.filename);
			
			for(final FileSummary fsa: COMPARISONS) {
				if(!fsa.anonymized) { continue; }
				if(!fsa.project.equals(fs.project)) { continue; }
				
				//Nifi FlowFileSupplier somehow got renamed…
				if(fs.filename.equals("FlowSupplier")) {
					if(!fsa.filename.equals("FlowFileSupplier")) { continue; }
				} else {
					if(!fsa.filename.equals(fs.filename)) { continue; }
				}
				
				counterpart = fsa;
				break;
			}
			
			Objects.requireNonNull(counterpart, "Null counterpart for: "+fs.filename);
			final double jaccard_diff = (fs.jaccard() - counterpart.jaccard());
			final double pair_jaccard_diff = (fs.pairwiseJaccard() - counterpart.pairwiseJaccard());
			
			jac_total_diff += jaccard_diff;
			
			if(!Double.isNaN(pair_jaccard_diff)) {
				pair_jac_total_diff += pair_jaccard_diff;
			} else {
				++pair_jac_anon_improve_not_counted;
			}
			
			if(jaccard_diff < 0) { ++jac_anon_improve; }
			if(pair_jaccard_diff < 0) { ++pair_jac_anon_improve; }
			
			lineBuilder.append(jaccard_diff+",");
			lineBuilder.append(pair_jaccard_diff+",");
			appendFileLines(COMP_FILE, lineBuilder.toString());
		}
		
		System.out.println();
		System.out.println("5-jaccard average = "+(jaccard_5_sum/(double)(200-nan_jaccard_5)));
		System.out.println("2-jaccard average = "+(jaccard_2_sum/(double)(200-nan_jaccard_2)));
		System.out.println("5-jaccard range   = "+(jaccard_5_max-jaccard_5_min));
		System.out.println("2-jaccard range   = "+(jaccard_2_max-jaccard_2_min));
		
		System.out.println("Average Improved 5-Jaccard: " + (jac_total_diff / (double) COMPARISONS.size()));
		System.out.println("Average Improved 2-Jaccard: " + (pair_jac_total_diff / ((double) COMPARISONS.size() - (double) pair_jac_anon_improve_not_counted)));
		
		System.out.println("Anonymizing Improved Jaccard-5: "+jac_anon_improve);
		System.out.println("Anonymizing Improved Jaccard-2: "+pair_jac_anon_improve);
		
		System.out.println();
		printAMCP();
	}
	
	/**
	 * Derby-ChatGPT-raw is a problem
	 */
	private static void specialAnalysis() {
		process(new AssistFile("research/llm-pattern-detection/@analysis/chatgpt/derby-chatgpt.txt"));
		
		System.out.println(ANSWERS);
		
		System.exit(0);
	}
	
	private static void computeJaccard() {
		final SetSimilarity<QuantifiedPattern> sim = new SetSimilarity<QuantifiedPattern>(
				new QuantifiedPattern(PatternType.NONE,100,100), 
				new QuantifiedPattern(PatternType.NON_PATTERN,100,100));
		
		final ArrayList<QuantizableFrame<FileSummary>> byJaccard = new ArrayList<QuantizableFrame<FileSummary>>();
		
		for(final FileSummary fs: COMPARISONS) {
			final double jaccardVal = sim.jaccard(fs, element -> element.correctness);
			final QuantizableFrame<FileSummary> frame = new QuantizableFrame<FileSummary>(fs, jaccardVal);
			byJaccard.add(frame);
		}
		
		Collections.sort((List<QuantizableFrame<FileSummary>>) byJaccard);
		
		for(final QuantizableFrame<FileSummary> fs: byJaccard) {
			String str = fs.data.toString();
			str = str.replaceAll("\\(size=5\\)", "");
			str = str.replaceAll("Aggregate Classification of ", "");
			
			final String[] fields = str.split("::");
			fields[0] = padStringTo(fields[0], 9);
			fields[1] = padStringTo(fields[1], 43);
			final String double_str = String.format("%.6f", fs.value);
			
			System.out.println(fields[0]+" : "+fields[1]+" : "+double_str);
		}
		
		multiDistance();
	}
	
	private static void loadDeanonymizer() {
		for(final String str: new File(BASE_DIR).list()) {
			if(str.startsWith("@")) { continue; }
			
			final File dir = new File(BASE_DIR+"/"+str);
			if(!dir.isDirectory()) { continue; }
			
			final String[] lines = getFileLines(dir.getPath()+"/@readme.txt");
			
			for(final String line: lines) {
				final String[] fields = line.split(" = ");
				fields[0] = fields[0].replaceAll("File: ", "");
				fields[0] = fields[0].replaceAll(".java", "");
				fields[1] = fields[1].replaceAll("class#", "");
				
				final Pair<String, Integer> key = new Pair<>(str, Integer.parseInt(fields[1]));
				final String[] pathFields = fields[0].split("/");
				DEANONYMIZER.put(key, pathFields[pathFields.length-1]);
			}
		}
	}

	private static void multiDistance() {
		/*****************************
		 * Compute Average Distances *
		 *****************************/
		final ArrayList<FileSummary> ordered_compare = new ArrayList<FileSummary>();
		ordered_compare.addAll(COMPARISONS);
		Collections.sort((List<FileSummary>) ordered_compare);
		
		
		System.out.println("\nFile Summaries: "+ordered_compare.size());
		for(final FileSummary fs: ordered_compare) {
			String str = fs.toString();
			str = str.replaceAll("\\(size=5\\)", "");
			str = str.replaceAll("Aggregate Classification of ", "");
			
			final String[] fields = str.split("::");
			fields[0] = padStringTo(fields[0], 9);
			fields[1] = padStringTo(fields[1], 43);
			final String double_str = String.format("%.6f", fs.averageDistance());
			
			System.out.println(fields[0]+" : "+fields[1]+" : "+double_str);
		}
		
		System.out.println(ANSWERS.size());
	}
	
	/**
	 * TODO need to cut off after 5 predictions
	 * @param file
	 */
	private static void process(final AssistFile file) {
		final ArrayList<String> sanityCheck = new ArrayList<>();
		final String filename = filename(file);
		final String[] filenameFields = filename.split("-");
		final boolean anonymized = (filenameFields.length == 3);
		
		System.out.println("Analyzing: "+filename);
		int added = 0;
		
		final String project = filenameFields[0];
		final LLM llm = LLM.parse(filenameFields[1]);
		
		AI_Classification aic = null; //
		
		int classNo = 0;
		String classname = null;
		
		final String[] fileLines = getFileLines(file.getPath());
		
		for(String line: fileLines) {
			if(line.contains("(idiom)")) {
				System.err.println("FileSummary"+line);
			}
			
			line = line.trim();
			
			if(line.startsWith("#")) {
				classname = anonymized ? validate(line, classNo) : extractClassName(line);
				
				++classNo;
				
				if(aic != null) { 
					ANSWERS.add(aic);
					++added;
				}
				
				if(classname != null) {
					final Pair<String, Integer> key = new Pair<>(project, classNo-1);
					
					if(classNo == 11) {
						System.err.println("File: '"+file+"' needs manual edits to ensure parsing compatibility");
					}
					
					final String myClassName = (!classname.startsWith("class#")) ? classname : DEANONYMIZER.get(key);
					
					//qp(DEANONYMIZER.get(key));
					
					aic = new AI_Classification(project, myClassName, llm, anonymized);
					sanityCheck.add(classname);
				} else {
					
				}
				
				continue;
			}
			
			if(line.startsWith("|") && !line.contains("---")) {
				QuantifiedPattern pattern = null;
				
				try {
					pattern = QuantifiedPattern.parse(line);
					if(ALLOW_HYBRID || !pattern.toString().startsWith("HYBRID")) {
						aic.add(pattern);
					}
				} catch(final Exception e) {
					//if(e instanceof NumberFormatException) { e.printStackTrace(); }
					
					if(QuantifiedPattern.containsPercentAtFieldNo(line, 2)) {
						System.err.println("\n"+line);
						System.err.println(e.getMessage());
						++debug_count;
					}
				}	
				continue;
			}
		}
		
		if(aic != null) {
			ANSWERS.add(aic);
			++added;
		}
		if(added != 10) {
			System.err.println("Added: "+added);
			System.err.println(sanityCheck);
		}
	}

	/**
	 * Extracts the class name from a line of code
	 * @param line
	 * @return
	 */
	private static String extractClassName(String line) {
		//clean LLM notes
		line = removeCharsBetweenDelimiters(line, "(", ")");
		//clean generics
		line = removeCharsBetweenDelimiters(line, "<", ">");
		line = removeCharsBetweenDelimiters(line, ",", ">");
		
		line = line.replaceAll("[#`\\*:]", "");
		line = line.replaceAll("&x20;", "");
		line = line.replaceAll("patterns detected", "");
		line = line.replaceAll(".txt", "");
		line = line.replaceAll("File", "");
		line = line.replaceAll("Class", "");
		
		line = line.trim();
		
		if(line.contains(".")) {
			String[] lineFields = line.split("[\\.]");
			line = lineFields[lineFields.length-1].trim();
		}
		
		if(line.contains("-")) {
			String[] lineFields = line.split("-");
			line = lineFields[lineFields.length-1].trim();
		}
		
		line = line.replaceAll("—", "").trim();
		
		while(Character.isDigit(line.charAt(0))) {
			line = line.substring(1).trim();
		}
		
		if(line.startsWith(")")) {
			line = line.substring(1);
		}
		line = line.trim();
		
		if(!line.contains(" ") && !line.equalsIgnoreCase("summary") && !line.equalsIgnoreCase("notes")) {
			return line;
		} else {
			//qerr(line);
			return null;
		}
	}
	
	public static void printAMCP() {
		for(final double threshold: AMCP_THRESHOLDS) {
			int total = 0;
			for(final FileSummary fs: COMPARISONS) {
				if(fs.avgMaxCertainty() > threshold) {
					++total;
				}
			}
			System.out.println("AMCP Threshold "+String.format("%.2f", threshold) + ": "+total);
		}
		
		
		for(double threshold = 0; threshold < 1; threshold += 0.05) {
			int total = 0;
			for(final FileSummary fs: COMPARISONS) {
				if(fs.maxCertaintyVariance() <= threshold) {
					++total;
				}
			}
			System.out.println("MCP Range Threshold "+String.format("%.2f", threshold) + ": "+total);
		}
	}
	
	/**
	 * 
	 * @param line
	 * @param classNo
	 * @return
	 */
	private static String validate(String line, final int classNo) {
		final String potentialName = "class#"+classNo;
		boolean ok = false;
		
		if(containsIgnoreCase(line, "file")) { ok = true; }
		if(containsIgnoreCase(line, "files")) { ok = false; }
		
		if(containsIgnoreCase(line, "class")) { ok = true; }
		//if(Assist.containsIgnoreCase(line, "interface")) { ok = true; }
		if(containsIgnoreCase(line, "divider")) { ok = true; }
		if(containsIgnoreCase(line, "ident")) { ok = true; }
		
		return ok ? potentialName : null;
	}
	
	public static final String[] getFileLines(final String filename) {
		final String retVal[];
		final ArrayList<String> fileLines = new ArrayList<String>();
		
		String line;
		
		try (final BufferedReader reader = new BufferedReader(new FileReader(filename))) {
			for(line = reader.readLine(); reader.ready(); line = reader.readLine()) {
				fileLines.add(line);
			}
			fileLines.add(line);
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
		
		retVal = new String[fileLines.size()];
		fileLines.toArray(retVal);
		return retVal;
	}
	
	static final void writeFileLines(final File outFile, String... lines) {
		Objects.requireNonNull(outFile, "A file must be specified!");
		
		if(lines == null) {
			return;
		}
		
		try (final PrintWriter writer = new PrintWriter(outFile)) {
			for(final Object line: lines) {
				writer.write((line != null) ? line.toString() + "\n" : "\n");
			}
		} catch (final FileNotFoundException e) {
			throw new RuntimeException(e);
		}
	}
	
	static final void appendFileLines(final File outFile, String... lines) {
		Objects.requireNonNull(outFile, "A file must be specified!");

		if(lines == null) {
			return;
		}
		
		try (final PrintWriter writer = new PrintWriter(new FileWriter(outFile, true))) {
			for(final Object line: lines) {
				writer.write((line != null) ? line.toString() + "\n" : "\n");
			}
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static final String removeCharsBetweenDelimiters(String line, final String delim1, final String delim2) {
		
		while(line.contains(delim1) && line.contains(delim2)) {
			final String lineAtStart = line;
			
			//index of the first delimiter
			final int start = line.indexOf(delim1);
			
			//front of the string before the first delimiter
			final String front = line.substring(0, start);
			
			//the string without the front
			final String minus_front = line.substring(start);
			
			//index of the last delimiter
			final int end = front.length()+minus_front.indexOf(delim2)+delim2.length();
			
			//front of the string before the first delimiter
			final String back = line.substring(end);
			
			line = front + back;
			
			if(lineAtStart.equals(line)) {
				throw new RuntimeException("Assist.removeCharsBetweenDelimiters() is on an infinite loop!"+
						"\nline      = " + line+
						"\nstart     = " + start+
						"\nend       = " + end);
			}
			
		}
		return line;
	}
	
	private static final String padStringTo(final String s, final int i) {
		final StringBuilder builder = new StringBuilder(s);
		while(builder.length() < i) {
			builder.append(' ');
		}
		
		return builder.toString();
	}
	
	private static final boolean containsIgnoreCase(final String searchIn, final String searchFor) {
		return (searchIn.toLowerCase().contains(searchFor.toLowerCase()));
 	}
	
	public static final boolean perfectEquals(final Object obj1, final Object obj2) {
		if((obj1 == null) != (obj2 == null)) { return false; }
		if(obj1 == null) { return true; }
		
		if(obj1.getClass().isArray() && obj2.getClass().isArray()) {
			return Arrays.deepEquals((Object[]) obj1, (Object[]) obj2);
		}
		
		return obj1.equals(obj2) && obj2.equals(obj1);
	}
	
	public static String filename(final File file) {
		final String[] fields = file.getPath().split("/");
		String namePortion = fields[fields.length-1];
		namePortion = namePortion.substring(0, namePortion.length()-JAVA_EXT.length()+1);
		return namePortion;
	}
	
	/**
	 * Determines if a string is Alpha-numeric
	 * @param str
	 * @return
	 */
	public static final boolean isInt(final String str) {
		return str.matches("-?\\d+");
	}
}

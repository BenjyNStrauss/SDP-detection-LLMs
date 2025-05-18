package anonymize;

import java.util.Hashtable;

/**
 * Class for anonymizing java code variables and constants
 * designed for use in a pattern-detecting neural network
 * 
 * @author Benjamin Strauss
 * 
 */

public class JavaAnonymizerStandalone {
	public final boolean preserve_non_sealed;
	
	private static final String[] DEFAULT_KEYWORDS = new String[]{
			"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", 
	        "class", "const", "continue", "default", "do", "double", "else", "enum", 
	        "extends", "final", "finally", "float", "for", "goto", "if", "implements", 
	        "import", "instanceof", "int", "interface", "long", "native", "new", "null", 
	        "package", "private", "protected", "public", "return", "short", "static", 
	        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", 
	        "transient", "try", "void", "volatile", "while",
	};
	
	private static final String[] ADDITIONAL_KEYWORDS = new String[]{
			"exports", "module", "non-sealed", "open", "opens", "permits",
			"provides", "record", "requires", "sealed", "to", "transitive",
			"uses", "var", "when", "with", "yield"
	};
	
	private static final String STRING = "String";
	
	private static final String[] JAVA_COMMON = new String[] {
			STRING, "java", "javax"
	};
	
	private final String[] keywords;
	
	/**
	 * Default constructor using default keywords
	 */
	public JavaAnonymizerStandalone() { this(DEFAULT_KEYWORDS); }
	
	/**
	 * 
	 * @param keywords
	 */
	public JavaAnonymizerStandalone(final String[] keywords) {
		this.keywords = (keywords != null) ? keywords : DEFAULT_KEYWORDS;
		preserve_non_sealed = stringArrayContains(keywords, "non-sealed", true);
	}
	
	/**
	 * Input is the java code, where line is it's own string
	 * @param code
	 * @return
	 */
	public String[] anonamize(String[] code) {
		//remove comments
		code = removeJavaComments(String.join("\n", code)).trim().split("\n");
		
		//Standardize the spacing
		code = fixSpacing(code);
		
		//remove empty lines
		String codeLine = String.join("\n", code);
		while(codeLine.contains("\n\n")) {
			codeLine = codeLine.replaceAll("\n\n", "\n");
		}
		
		final Hashtable<String, Integer> tokens = new Hashtable<String, Integer>();
		final Hashtable<String, Integer> literals = new Hashtable<String, Integer>();
		
		final char[] text = codeLine.toCharArray();
		final StringBuilder outputBuilder = new StringBuilder();
		
		final StringBuilder tokenBuilder = new StringBuilder();
		boolean doubleQuoted = false;
		boolean singleQuoted = false;
		int singleQuoteStart = -1;
		int doubleQuoteStart = -1;
		
		int identifierStart = -1;
		boolean inJavaIdent = false;
		
		for(int ii = 0; ii < text.length; ++ii) {
			if(isStringQuote(text, ii)) {
				//if we are not doubleQuoted, record the start of the doubleQuoted
				if(!doubleQuoted) { doubleQuoteStart = ii; }
				
				//we are starting or stopping a string literal
				doubleQuoted = !doubleQuoted;
				
				//if we have finished reading the literal -- record it
				if(!doubleQuoted) {
					for(int jj = doubleQuoteStart; jj <= ii; ++jj) {
						tokenBuilder.append(text[jj]);
					}
					
					if(!literals.containsKey(tokenBuilder.toString())) {
						literals.put(tokenBuilder.toString(), literals.size());
					}
					
					final int literalNo = literals.get(tokenBuilder.toString());
					
					//System.out.println("Found literal: $"+literalNo+" :: "+tokenBuilder.toString());
					
					outputBuilder.append("\"literal$"+literalNo+"\"");
					
					//reset tokenBuilder
					tokenBuilder.setLength(0);
					doubleQuoteStart = -1;
				}
			} else if(doubleQuoted) {
				//we are in a string literal
				continue;
			} else if(isCharQuote(text, ii)) {
				//if we are not doubleQuoted, record the start of the doubleQuoted
				if(!singleQuoted) { singleQuoteStart = ii; }
				
				//we are starting or stopping a string literal
				singleQuoted = !singleQuoted;
				
				//if we have finished reading the literal -- record it
				if(!singleQuoted) {
					for(int jj = singleQuoteStart; jj <= ii; ++jj) {
						tokenBuilder.append(text[jj]);
					}
					
					if(!literals.containsKey(tokenBuilder.toString())) {
						literals.put(tokenBuilder.toString(), literals.size());
					}
					
					final int literalNo = literals.get(tokenBuilder.toString());
					
					//System.out.println("Found char literal: $"+literalNo+" :: "+tokenBuilder.toString());
					
					outputBuilder.append("'char-literal$"+literalNo+"'");
					
					//reset tokenBuilder
					tokenBuilder.setLength(0);
					singleQuoteStart = -1;
				}
			} else if(singleQuoted) {
				//we are in a char literal
				continue;
			} else {
				if(Character.isJavaIdentifierStart(text[ii])) {
					inJavaIdent = true;
					identifierStart = ii;
					tokenBuilder.append(text[ii]);
					
				} else if(!Character.isJavaIdentifierPart(text[ii])) {
					//if we encounter a '-', make sure it doesn't come from "non-sealed"
					if(text[ii] == '-' && preserve_non_sealed && isNonSealedHyphen(text, ii)) {
						outputBuilder.append(text[ii]);
						continue;
					}
					
					inJavaIdent = false;
					
					if(identifierStart != -1) {
						/*
						 * build the identifier token
						 * why does it only work when jj < ii-1?
						 */
						for(int jj = identifierStart; jj < ii-1; ++jj) {
							tokenBuilder.append(text[jj]);
						}
						
						if(!tokens.containsKey(tokenBuilder.toString())) {
							tokens.put(tokenBuilder.toString(), tokens.size());
						}
						
						final int identifierNo = tokens.get(tokenBuilder.toString());
						
						//System.out.println("Found identifier: $"+identifierNo+" :: "+tokenBuilder.toString());
						
						if(stringArrayContains(keywords, tokenBuilder.toString(), false)) {
							outputBuilder.append(tokenBuilder.toString());
						} else {
							outputBuilder.append("ident$"+identifierNo);
						}
						
						//reset outputBuilder
						tokenBuilder.setLength(0);
						identifierStart = -1;
					}
				}
				
				if(!inJavaIdent) {
					outputBuilder.append(text[ii]);
				}
			}
		}
		
		codeLine = outputBuilder.toString();
		
		return codeLine.split("\n");
	}

	/**
	 * 
	 * @param text
	 * @param index
	 * @return
	 */
	private boolean isCharQuote(final char[] text, final int index) {
		if(text[index] == '\'') {
			if(text[index-1] == '\\') {
				return false;
			} else {
				return true;
			}
		}
		return false;
	}

	/**
	 * Determines if a character starts a quoted string literal
	 * @param text
	 * @param index
	 * @return
	 */
	private static boolean isStringQuote(final char[] text, final int index) {
		if(text[index] == '"') {
			switch(text[index-1]) {
			case '\\':
				int escapes = 0;
				
				loop:
				for(int escIndex = index-1; escIndex >= 0; --escIndex) {
					if(text[escIndex] != '\\') {
						break loop;
					} else {
						++escapes;
					}
				}
				//if even number of preceding escapes
				if(escapes % 2 == 0) {
					return true;
				} else {
					return false;
				}
			case '\'':
				//a single quoted double quote, not the start/end of a literal
				if(text[index-1] == text[index+1]) {
					return false;
				} else {
					return true;
				}
			}
			return true;
		}
		return false;
	}
	
	/**
	 * determine if a given hyphen is the hyphen in "non-sealed"
	 * @param text
	 * @param ii
	 * @return
	 */
	private boolean isNonSealedHyphen(final char[] text, final int ii) {
		if(ii < 3 || ii >= text.length-6) { return false; }
		
		if(ii != 4 || Character.isJavaIdentifierPart(text[ii-4])) { return false; }
		if(text[ii-3] != 'n') { return false; }
		if(text[ii-2] != 'o') { return false; }
		if(text[ii-1] != 'n') { return false; }
		if(text[ii+1] != 's') { return false; }
		if(text[ii+2] != 'e') { return false; }
		if(text[ii+3] != 'a') { return false; }
		if(text[ii+4] != 'l') { return false; }
		if(text[ii+5] != 'e') { return false; }
		if(text[ii+6] != 'd') { return false; }
		if(ii != text.length-7 || Character.isJavaIdentifierPart(text[ii+7])) { return false; }
		
		return true;
	}
	
	/**
	 * 
	 * @param code
	 * @return
	 */
	protected static String[] fixSpacing(final String[] code) {
		for(int index = 0; index < code.length; ++index) {
			code[index] = code[index].replaceAll("\\s+", " ").trim();
		}
		
		return code;
	}
	
	/**
	 * Removes Java comments
	 * 
	 * @param text
	 * @return
	 */
	public static String removeJavaComments(final String text) {
		final StringBuilder builder = new StringBuilder();
		final char[] lineChars = text.toCharArray();
		
		boolean quoted = false;
		boolean commented = false;
		boolean line_commented = false;
		
		for(int pos = 0; pos < lineChars.length; ++pos) {
			switch(lineChars[pos]) {
			case '"':
				if(!(commented || line_commented)) { 
					//ignore escaped quotes
					if(lineChars[pos-1] != '\\') {
						quoted = !quoted;
					}
					builder.append(lineChars[pos]);
				}
				break;
			case '/':
				if(quoted) {
					builder.append(lineChars[pos]);
				} else if(pos < lineChars.length-1 && lineChars[pos+1] == '/') {
					line_commented = true;
				} else if(pos < lineChars.length-1 && !line_commented && lineChars[pos+1] == '*') {
					commented = true;
				} if(pos > 0 && commented && !line_commented && lineChars[pos-1] == '*') {
					commented = false;
				}
				break;
			case '\n':
				line_commented = false;
				builder.append(lineChars[pos]);
				break;
			default:
				if(!(commented || line_commented)) {
					builder.append(lineChars[pos]);
				}
			}	
		}
		
		return builder.toString();
	}
	
	/**
	 * Determine if an array of Strings contains a given string
	 * @param array
	 * @param searchTerm
	 * @param ignoreCase
	 * @return
	 */
	public static final boolean stringArrayContains(final String array[], String searchTerm, final boolean ignoreCase) {
		if(ignoreCase) { 
			for(final String str: array) { 
				if(str.equalsIgnoreCase(searchTerm)) { return true; }
			}
		} else {
			for(final String str: array) {
				if(str.equals(searchTerm)) { return true; }
			}
		}
		
		return false;
	}
}

package org.asf.centuria.textfilter.impl.filterrunners;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import org.asf.centuria.textfilter.FilterMode;
import org.asf.centuria.textfilter.FilterSeverity;
import org.asf.centuria.textfilter.IFilterRunner;
import org.asf.centuria.textfilter.IResultStringBuilder;
import org.asf.centuria.textfilter.PhraseFilter;
import org.asf.centuria.textfilter.PhraseFilterSet;
import org.asf.centuria.textfilter.context.IContextExpression;
import org.asf.centuria.textfilter.context.TextFilterContextMemory;
import org.asf.centuria.textfilter.result.FilterResult;
import org.asf.centuria.textfilter.result.TextPart;
import org.asf.centuria.textfilter.result.WordMatch;

public class DefaultFilterRunner implements IFilterRunner {

	private String replaceDoubleSpaces(String in) {
		while (in.contains("  "))
			in = in.replace("  ", " ");
		return in;
	}

	private boolean match(FilterMode mode, String text, String filterWord, String filterVariant) {
		// Run for specific modes
		if (filterWord != null) {
			// Single-word comparison

			// Try contains/equals based comparison first, this only works when the filter
			// variant does NOT contain spaces with WORD_CONTAINS, except in COMBINED mode,
			// which is when its worth a try
			if (!filterVariant.contains(" ") || mode == FilterMode.PHRASE_COMBINED
					|| mode == FilterMode.WORD_COMBINED) {
				if (mode == FilterMode.WHOLE_PHRASE || mode == FilterMode.PHRASE_COMBINED) {
					// Check phrase by comparing entire phrase
					if (replaceDoubleSpaces(filterWord).equalsIgnoreCase(replaceDoubleSpaces(filterVariant))
							|| replaceDoubleSpaces(filterWord).replaceAll("[^A-Za-z0-9 ]", "")
									.equalsIgnoreCase(replaceDoubleSpaces(filterVariant)))
						return true;
				}
				if (mode == FilterMode.PHRASE_COMBINED) {
					// Check phrase by comparing entire phrase
					// Strip spaces from the input variant
					if (replaceDoubleSpaces(filterWord).equalsIgnoreCase(filterVariant.replace(" ", "").toLowerCase())
							|| replaceDoubleSpaces(filterWord).replaceAll("[^A-Za-z0-9 ]", "")
									.equalsIgnoreCase(filterVariant.replace(" ", "").toLowerCase()))
						return true;
				}
				if (mode == FilterMode.WORD_CONTAINS || mode == FilterMode.WORD_COMBINED) {
					// Check if the word given as input contains the matcher
					if (replaceDoubleSpaces(filterWord).toLowerCase()
							.contains(replaceDoubleSpaces(filterVariant).toLowerCase())
							|| replaceDoubleSpaces(filterWord).replaceAll("[^A-Za-z0-9 ]", "").toLowerCase()
									.contains(replaceDoubleSpaces(filterVariant).toLowerCase()))
						return true;
				}
				if (mode == FilterMode.WORD_COMBINED) {
					// Check if the word given as input contains the matcher
					// Strip spaces from the input variant
					if (replaceDoubleSpaces(filterWord).toLowerCase()
							.contains(filterVariant.replace(" ", "").toLowerCase())
							|| replaceDoubleSpaces(filterWord).replaceAll("[^A-Za-z0-9 ]", "").toLowerCase()
									.contains(filterVariant.replace(" ", "").toLowerCase()))
						return true;
				}
			}

			// Filter returns false
			return false;
		}

		// Try with advanced comparison by creating a full text string and comparing
		// against it with the matchers for each mode
		String textFull = " " + text.replaceAll("[^A-Za-z0-9 ]", "") + " ";
		String textFullOrig = " " + text + " ";
		if (mode == FilterMode.WHOLE_PHRASE || mode == FilterMode.PHRASE_COMBINED) {
			// Check if the full phrase is present in the input string
			if (replaceDoubleSpaces(textFullOrig).toLowerCase()
					.contains(" " + replaceDoubleSpaces(filterVariant.toLowerCase()) + " ")
					|| replaceDoubleSpaces(textFull).toLowerCase()
							.contains(" " + replaceDoubleSpaces(filterVariant.toLowerCase()) + " "))
				return true;
		}
		if (mode == FilterMode.PHRASE_COMBINED) {
			// Check if the full phrase is present in the input string
			// Strip spaces from input variant
			if (replaceDoubleSpaces(textFullOrig).toLowerCase()
					.contains(" " + filterVariant.replace(" ", "").toLowerCase())
					|| replaceDoubleSpaces(textFull).toLowerCase()
							.contains(" " + filterVariant.replace(" ", "").toLowerCase() + " "))
				return true;
		}
		if (mode == FilterMode.WORD_CONTAINS || mode == FilterMode.WORD_COMBINED) {
			// Check phrase
			boolean match = false;
			boolean foundStart = false;

			// Create array of the phrase
			String[] variantWords = replaceDoubleSpaces(filterVariant).split(" ");
			int i = 1;

			// Create array of the input
			String[] contents = text.split(" ");

			// Ignore empty variants
			if (variantWords.length != 0) {
				// Select first variant
				String firstVariant = variantWords[0];
				String firstVariantStripped = firstVariant;

				// Go through input
				for (String word : contents) {
					// Skip empty segments
					if (word.isEmpty())
						continue;
					String wordStripped = word.replaceAll("[^A-Za-z0-9 ]", "");

					// Check if we already found the start of the phrase
					if (!foundStart) {
						// Found the starting phrase
						if (word.toLowerCase().contains(firstVariant.toLowerCase())
								|| wordStripped.toLowerCase().contains(firstVariantStripped.toLowerCase())) {
							// Mark that we located the first word in the filter phrase
							foundStart = true;

							// Mark this as a match for now
							match = true;
						}
					} else {
						// Check next word

						// End if we are at the limit of the phrase
						if (i == variantWords.length)
							break;

						// Check next word
						String variant = variantWords[i++];
						if (!word.toLowerCase().contains(variant.toLowerCase())
								&& !wordStripped.toLowerCase().contains(variant.toLowerCase())) {
							// No match, reset
							match = false;
							foundStart = false;
							i = 1;
						}
					}
				}

				// Check result, make sure the entire variant was found, and check if its a
				// match, otherwise continue
				if (i == variantWords.length && match)
					return true;
			}
		}
		if (mode == FilterMode.WORD_COMBINED) {
			// Rerun with spaces stripped
			if (match(FilterMode.WORD_CONTAINS, text, filterWord, filterVariant.replace(" ", "").toLowerCase()))
				return true;
		}

		// Filter returns false
		return false;
	}

	@Override
	public boolean isFiltered(TextFilterContextMemory memory, FilterSeverity minimalSeverity, String text,
			boolean strictMode, Map<String, PhraseFilterSet> filters, String[] tags) {
		// Check phrase-based filters first, they are most intensive, single-word
		// filters are done using a less intensive strategy
		if (filters.values().stream().filter(set -> {
			// Check set tags
			String[] setTags = set.getSetTags();
			if (setTags.length != 0
					&& !Stream.of(setTags).anyMatch(t -> Stream.of(tags).anyMatch(tag -> tag.equalsIgnoreCase(t))))
				return false;
			return true;
		}).anyMatch(t -> Stream.of(t.getFilteredPhrases()).anyMatch(filter -> {
			// Check filter strict mode
			if (strictMode || (filter.getSeverity() != FilterSeverity.USER_STRICT_MODE)) {
				for (FilterMode mode : filter.getModes()) {
					// Check default
					if (filter.getSeverity().ordinal() >= minimalSeverity.ordinal() && filter.getPhrase().contains(" ")
							&& match(mode, text, null, filter.getPhrase()) && contextCompare(filter, text, memory))
						return true;

					// Check variants
					for (String variant : filter.getVariants())
						if (filter.getSeverity().ordinal() >= minimalSeverity.ordinal() && variant.contains(" ")
								&& match(mode, text, null, variant) && contextCompare(filter, text, memory))
							return true;
				}
			}

			// Unfiltered
			return false;
		}))) {
			// Filtered
			return true;
		}

		// Check word-by-word, using a less intensive strategy
		for (String word : text.split(" ")) {
			// Check filters
			String wordStripped = word.replaceAll("[^A-Za-z0-9 ]", "");
			if (filters.values().stream().filter(set -> {
				// Check set tags
				String[] setTags = set.getSetTags();
				if (setTags.length != 0
						&& !Stream.of(setTags).anyMatch(t -> Stream.of(tags).anyMatch(tag -> tag.equalsIgnoreCase(t))))
					return false;
				return true;
			}).anyMatch(t -> Stream.of(t.getFilteredPhrases()).anyMatch(filter -> {
				// Check filter
				if (strictMode || (filter.getSeverity() != FilterSeverity.USER_STRICT_MODE)) {
					for (FilterMode mode : filter.getModes()) {
						// Check phrase
						if (filter.getSeverity().ordinal() >= minimalSeverity.ordinal()
								&& (match(mode, null, word, filter.getPhrase())
										|| match(mode, null, wordStripped, filter.getPhrase()))
								&& contextCompare(filter, text, memory))
							return true;
						for (String variant : filter.getVariants())
							if (filter.getSeverity().ordinal() >= minimalSeverity.ordinal()
									&& (match(mode, null, word, variant) || match(mode, null, wordStripped, variant))
									&& contextCompare(filter, text, memory))
								return true;
					}
				}

				// Unfiltered
				return false;
			}))) {
				// Filtered
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean shouldFilterMute(TextFilterContextMemory memory, FilterSeverity minimalSeverity, String text,
			Map<String, PhraseFilterSet> filters, String[] tags) {
		// Check phrase-based filters first, they are most intensive, single-word
		// filters are done using a less intensive strategy
		if (filters.values().stream().filter(set -> {
			// Check set tags
			String[] setTags = set.getSetTags();
			if (setTags.length != 0
					&& !Stream.of(setTags).anyMatch(t -> Stream.of(tags).anyMatch(tag -> tag.equalsIgnoreCase(t))))
				return false;
			return true;
		}).anyMatch(t -> Stream.of(t.getFilteredPhrases()).anyMatch(filter -> {
			// Check filter strict mode
			if (filter.getSeverity() == FilterSeverity.INSTAMUTE) {
				for (FilterMode mode : filter.getModes()) {
					// Check default
					if (filter.getSeverity().ordinal() >= minimalSeverity.ordinal() && filter.getPhrase().contains(" ")
							&& match(mode, text, null, filter.getPhrase()) && contextCompare(filter, text, memory))
						return true;

					// Check variants
					for (String variant : filter.getVariants())
						if (filter.getSeverity().ordinal() >= minimalSeverity.ordinal() && variant.contains(" ")
								&& match(mode, text, null, variant) && contextCompare(filter, text, memory))
							return true;
				}
			}

			// Unfiltered
			return false;
		}))) {
			// Filtered, return
			return true;
		}

		// Check word-by-word, using a less intensive strategy
		for (String word : text.split(" ")) {
			// Check filters
			String wordStripped = word.replaceAll("[^A-Za-z0-9 ]", "");
			if (filters.values().stream().filter(set -> {
				// Check set tags
				String[] setTags = set.getSetTags();
				if (setTags.length != 0
						&& !Stream.of(setTags).anyMatch(t -> Stream.of(tags).anyMatch(tag -> tag.equalsIgnoreCase(t))))
					return false;
				return true;
			}).anyMatch(t -> Stream.of(t.getFilteredPhrases()).anyMatch(filter -> {
				// Check filter
				if (filter.getSeverity() == FilterSeverity.INSTAMUTE) {
					for (FilterMode mode : filter.getModes()) {
						// Check phrase
						if (filter.getSeverity().ordinal() >= minimalSeverity.ordinal()
								&& (match(mode, null, word, filter.getPhrase())
										|| match(mode, null, wordStripped, filter.getPhrase()))
								&& contextCompare(filter, text, memory))
							return true;
						for (String variant : filter.getVariants())
							if (filter.getSeverity().ordinal() >= minimalSeverity.ordinal()
									&& (match(mode, null, word, variant) || match(mode, null, wordStripped, variant))
									&& contextCompare(filter, text, memory))
								return true;
					}
				}

				// Unfiltered
				return false;
			}))) {
				// Filtered
				return true;
			}
		}

		return false;
	}

	private boolean contextCompare(PhraseFilter filter, String text, TextFilterContextMemory memory) {
		// Check context exclude
		for (IContextExpression expression : filter.getContextExcludeExpressions()) {
			if (matchExpression(expression, filter, text, false, memory)) {
				// Expression matches
				return false; // Excluded by context
			}
		}
		for (IContextExpression expression : filter.getGlobalContextExcludeExpressions()) {
			if (matchExpression(expression, filter, text, true, memory)) {
				// Expression matches
				return false; // Excluded by context
			}
		}

		// Check context include
		boolean found = false;
		boolean hadExpression = false;
		for (IContextExpression expression : filter.getContextIncludeExpressions()) {
			hadExpression = true;
			if (matchExpression(expression, filter, text, false, memory)) {
				// Expression matches
				found = true;
				break;
			}
		}
		if (!found) {
			for (IContextExpression expression : filter.getGlobalContextIncludeExpressions()) {
				hadExpression = true;
				if (matchExpression(expression, filter, text, true, memory)) {
					// Expression matches
					found = true;
					break;
				}
			}
		}
		if (hadExpression && !found) {
			// Missing include
			return false;
		}

		// Success
		return true;
	}

	private boolean matchExpression(IContextExpression expression, PhraseFilter filter, String text, boolean useGlobal,
			TextFilterContextMemory memory) {
		return expression.compare((phrase) -> {
			// Match phrase

			// First, check local

			// Check phrase-based filters first, they are most intensive, single-word
			// filters are done using a less intensive strategy

			// Check filter strict mode
			for (String variant : phrase.getAllPhrases()) {
				for (FilterMode mode : phrase.getModesFor(variant)) {
					// Check variants
					if (variant.contains(" ") && match(mode, text, null, variant))
						return true;
				}
			}

			// Check word-by-word, using a less intensive strategy
			for (String word : text.split(" ")) {
				// Check filter
				String wordStripped = word.replaceAll("[^A-Za-z0-9 ]", "");
				for (String variant : phrase.getAllPhrases()) {
					for (FilterMode mode : phrase.getModesFor(variant)) {
						// Check phrase
						if (match(mode, null, word, variant) || match(mode, null, wordStripped, variant))
							return true;
					}
				}
			}

			// If enabled, check global
			if (useGlobal && memory != null) {
				// Run through messages
				FilterResult[] messages = memory.getMessagesInContext();
				for (int i = messages.length - 1; i >= 0; i--) {
					FilterResult message = messages[i];

					// Check phrase-based filters first, they are most intensive, single-word
					// filters are done using a less intensive strategy

					// Check filter strict mode
					for (String variant : phrase.getAllPhrases()) {
						for (FilterMode mode : phrase.getModesFor(variant)) {
							// Check variants
							if (variant.contains(" ") && match(mode, message.getOriginalText(), null, variant))
								return true;
						}
					}

					// Check word-by-word, using a less intensive strategy
					for (String word : message.getOriginalText().split(" ")) {
						// Check filter
						String wordStripped = word.replaceAll("[^A-Za-z0-9 ]", "");
						for (String variant : phrase.getAllPhrases()) {
							for (FilterMode mode : phrase.getModesFor(variant)) {
								// Check phrase
								if (match(mode, null, word, variant) || match(mode, null, wordStripped, variant))
									return true;
							}
						}
					}
				}
			}

			// No match
			return false; // No match
		});
	}

	private class CurrentTextPart {
		public String text = "";

		public boolean initialized = false;

		public boolean flagged = false;

		public FilterSeverity severity = FilterSeverity.NONE;
		public String primaryReason = null;
		public PhraseFilter filter = null;

		public ArrayList<String> matchedPhrases = new ArrayList<String>();
		public ArrayList<WordMatch> matchInstances = new ArrayList<WordMatch>();

		public TextPart toPart() {
			return new TextPart(text, flagged, severity, filter, primaryReason,
					matchInstances != null ? matchInstances.toArray(t -> new WordMatch[t]) : new WordMatch[0]);
		}
	}

	private class MatchOutput {
		public int newIndex = 0;
		public boolean match;

		public MatchOutput(boolean match, int index) {
			newIndex = index;
			this.match = match;
		}
	}

	private MatchOutput matchCheck(FilterMode mode, String variant, String word, String text, int i, String[] words) {
		switch (mode) {

		case WHOLE_PHRASE:
		case PHRASE_COMBINED: {
			boolean tryCombinedMode = mode == FilterMode.PHRASE_COMBINED;
			boolean phraseContainsSpace = variant.contains(" ");

			// Check mode
			if (phraseContainsSpace) {
				// Full phrase compare

				// Check if the starting word matches
				String[] variantWords = replaceDoubleSpaces(variant).split(" ");
				if (variantWords.length >= 1 && word.equalsIgnoreCase(variantWords[0])
						|| word.replaceAll("[^A-Za-z0-9 ]", "").equalsIgnoreCase(variantWords[0])) {
					// Match
					int start = i;
					boolean match = true;
					int indexInVariant = 0;
					int i2 = start;
					for (i2 = start; i2 < words.length; i2++) {
						String wordMatcher = words[i2];

						// Check word
						if (wordMatcher.isEmpty()) {
							// Skip empty
							continue;
						}

						// Verify word
						if (!wordMatcher.equalsIgnoreCase(variantWords[indexInVariant]) && !wordMatcher
								.replaceAll("[^A-Za-z0-9 ]", "").equalsIgnoreCase(variantWords[indexInVariant])) {
							// Invalid
							match = false;
							break;
						}
						indexInVariant++;
						if (indexInVariant == variantWords.length) {
							// Matched
							match = true;
							break;
						}
					}
					if (indexInVariant != variantWords.length)
						match = false;
					if (match) {
						return new MatchOutput(true, i2);
					}
				}
			} else {
				// Variant doesnt contains a space, simple compare
				if (word.equalsIgnoreCase(variant) || word.replaceAll("[^A-Za-z0-9 ]", "").equalsIgnoreCase(variant)) {
					// Match
					return new MatchOutput(true, i);
				}
			}

			// No match, rerun without spaces
			if (phraseContainsSpace && tryCombinedMode) {
				// Rerun
				return matchCheck(FilterMode.WHOLE_PHRASE, variant.replace(" ", ""), word, text, i, words);
			}
			break;
		}

		case WORD_CONTAINS:
		case WORD_COMBINED: {
			boolean tryCombinedMode = mode == FilterMode.WORD_COMBINED;
			boolean phraseContainsSpace = variant.contains(" ");

			// Check mode
			if (phraseContainsSpace) {
				// Full string compare

				// Check if the starting word matches
				String[] variantWords = replaceDoubleSpaces(variant).split(" ");
				if (variantWords.length >= 1 && word.toLowerCase().contains(variantWords[0].toLowerCase())
						|| word.toLowerCase().replaceAll("[^A-Za-z0-9 ]", "").contains(variantWords[0].toLowerCase())) {
					int start = i;
					boolean match = true;
					int indexInVariant = 0;
					int i2 = start;
					for (i2 = start; i2 < words.length; i2++) {
						String wordMatcher = words[i2];

						// Check word
						if (word.isEmpty()) {
							// Skip empty
							continue;
						}

						// Verify word
						indexInVariant++;
						if (indexInVariant == variantWords.length) {
							// Matched
							match = true;
							break;
						}
						if (!wordMatcher.toLowerCase().contains(variantWords[indexInVariant].toLowerCase())
								&& !wordMatcher.toLowerCase().replaceAll("[^A-Za-z0-9 ]", "")
										.contains(variantWords[indexInVariant].toLowerCase())) {
							// Invalid
							match = false;
							break;
						}
					}
					if (indexInVariant != variantWords.length)
						match = false;
					if (match) {
						return new MatchOutput(true, i2);
					}
				}
			} else {
				// Variant doesnt contains a space, simple compare
				if (word.toLowerCase().contains(variant.toLowerCase())
						|| word.toLowerCase().replaceAll("[^A-Za-z0-9 ]", "").contains(variant.toLowerCase())) {
					// Match
					return new MatchOutput(true, i);
				}
			}

			// No match, rerun without spaces
			if (phraseContainsSpace && tryCombinedMode) {
				// Rerun
				return matchCheck(FilterMode.WORD_CONTAINS, variant.replace(" ", ""), word, text, i, words);
			}
			break;
		}

		}

		// No match
		return new MatchOutput(false, i);
	}

	@Override
	public FilterResult filter(TextFilterContextMemory memory, FilterSeverity minimalSeverity, String text,
			boolean strictMode, Map<String, PhraseFilterSet> filters, String[] tags,
			IResultStringBuilder stringBuilder) {
		ArrayList<TextPart> messageParts = new ArrayList<TextPart>();
		ArrayList<String> matchedPhrases = new ArrayList<String>();
		ArrayList<WordMatch> matchedWords = new ArrayList<WordMatch>();
		CurrentTextPart currentPart = null;

		// Get filters
		PhraseFilterSet[] sets = filters.values().stream().filter(set -> {
			// Check set tags
			String[] setTags = set.getSetTags();
			if (setTags.length != 0
					&& !Stream.of(setTags).anyMatch(t -> Stream.of(tags).anyMatch(tag -> tag.equalsIgnoreCase(t))))
				return false;
			return true;
		}).toArray(t -> new PhraseFilterSet[t]);
		ArrayList<PhraseFilter> phraseFilters = new ArrayList<PhraseFilter>();
		for (PhraseFilterSet set : sets) {
			for (PhraseFilter filter : set.getFilteredPhrases()) {
				if (strictMode || filter.getSeverity() != FilterSeverity.USER_STRICT_MODE)
					phraseFilters.add(filter);
			}
		}

		// Go through message
		String[] words = text.split(" ");
		for (int i = 0; i < words.length; i++) {
			String word = words[i];

			// Check word
			if (word.isEmpty()) {
				// Add space to current part
				if (currentPart == null) {
					currentPart = new CurrentTextPart();
					currentPart.text = "";
					currentPart.initialized = false;
				} else {
					// Check censor state, if it was a flag, reset
					if (currentPart.flagged && currentPart.initialized) {
						// Add part
						messageParts.add(currentPart.toPart());

						// Reset
						currentPart = new CurrentTextPart();
						currentPart.text = "";
						currentPart.initialized = false;
					} else {
						// Add to existing part
						currentPart.text += " ";
					}
				}

				// Skip
				continue;
			}

			// Handle word
			boolean hasMatch = false;
			PhraseFilter matchedFilter = null;
			String[] matchedPhraseParts = new String[0];
			for (PhraseFilter filter : phraseFilters) {
				String[] phrases = filter.getAllPhrases();

				// Go through phrase list
				// First handle space phrases
				for (String variant : phrases) {
					// Check mode
					for (FilterMode mode : filter.getModes()) {
						MatchOutput matchRes = matchCheck(mode, variant, word, text, i, words);
						if (matchRes.match && filter.getSeverity().ordinal() >= minimalSeverity.ordinal()
								&& contextCompare(filter, text, memory)) {
							// Update index and collect match data
							matchedFilter = filter;

							// Find any with higher severity
							for (PhraseFilter filter2 : phraseFilters) {
								boolean match2 = false;
								String[] phrases2 = filter2.getAllPhrases();
								for (String variant2 : phrases2) {
									// Check mode
									for (FilterMode mode2 : filter2.getModes()) {
										MatchOutput matchRes2 = matchCheck(mode2, variant2, word, text, i, words);
										if (matchRes2.match
												&& filter2.getSeverity().ordinal() >= minimalSeverity.ordinal()
												&& filter2.getSeverity().ordinal() > matchedFilter.getSeverity()
														.ordinal()
												&& contextCompare(filter2, text, memory)) {
											// Higher match
											match2 = true;
											matchRes = matchRes2;
											matchedFilter = filter2;
											break;
										}
									}
									if (match2)
										break;
								}
								if (match2)
									break;
							}

							// Apply
							matchedPhraseParts = Arrays.copyOfRange(words, i, matchRes.newIndex + 1);
							i = matchRes.newIndex;
							hasMatch = true;
							break;
						}
						if (hasMatch)
							break;
					}
					if (hasMatch)
						break;
				}
				if (hasMatch)
					break;
			}

			// Check result
			if (hasMatch) {
				// Handle match

				// Generate string
				String textParts = "";
				for (String part : matchedPhraseParts) {
					if (textParts.isEmpty())
						textParts = part;
					else
						textParts += " " + part;
				}

				// If needed, add existing part to list and reset
				// Check part state, if its not another censor, add to list
				// Also reset if the matched filter isnt the same filter
				if (currentPart != null && currentPart.initialized
						&& (!currentPart.flagged || currentPart.filter != matchedFilter)) {
					// Add to list
					messageParts.add(currentPart.toPart());

					// Reset
					currentPart = new CurrentTextPart();
					currentPart.initialized = true;
					currentPart.text = "";
				}

				// If the part isnt initialized, reset it
				if (currentPart != null && !currentPart.initialized) {
					// Add to list
					messageParts.add(currentPart.toPart());

					// Reset
					currentPart = new CurrentTextPart();
					currentPart.initialized = true;
					currentPart.text = "";
				}

				// Create text part if needed
				if (currentPart == null) {
					currentPart = new CurrentTextPart();
					currentPart.initialized = true;
				}
				boolean wasUninitialzied = !currentPart.initialized;
				currentPart.initialized = true;
				currentPart.flagged = true;

				// Generate wordmatch
				WordMatch wordMatch = new WordMatch(matchedFilter, textParts);

				// Add to current part and overall result
				if (!currentPart.matchedPhrases.contains(textParts)) {
					currentPart.matchedPhrases.add(textParts);
					currentPart.matchInstances.add(wordMatch);
				}
				if (!matchedPhrases.contains(textParts)) {
					matchedPhrases.add(textParts);
					matchedWords.add(wordMatch);
				}

				// Add part information
				currentPart.filter = matchedFilter;
				currentPart.primaryReason = matchedFilter.getReason();
				currentPart.severity = matchedFilter.getSeverity();
				if (currentPart.text.isEmpty() && !wasUninitialzied)
					currentPart.text = textParts;
				else
					currentPart.text += " " + textParts;
			} else {
				// No match, regular result

				// If needed, add existing part to list and reset
				// Check part state, if its a previous censor, reset
				if (currentPart != null && currentPart.initialized && currentPart.flagged) {
					// Add to list
					messageParts.add(currentPart.toPart());

					// Reset
					currentPart = new CurrentTextPart();
					currentPart.initialized = true;
					currentPart.text = "";
				}

				// Create text part if needed
				if (currentPart == null) {
					currentPart = new CurrentTextPart();
					currentPart.initialized = true;
				}
				boolean wasUninitialzied = !currentPart.initialized;
				currentPart.initialized = true;
				currentPart.flagged = false;

				// Add text
				if (currentPart.text.isEmpty() && !wasUninitialzied)
					currentPart.text = word;
				else
					currentPart.text += " " + word;
			}
		}

		// Add last part if needed
		if (currentPart != null) {
			messageParts.add(new TextPart(currentPart.text, currentPart.flagged, currentPart.severity,
					currentPart.filter, currentPart.primaryReason,
					currentPart.flagged ? currentPart.matchInstances.toArray(t -> new WordMatch[t])
							: new WordMatch[0]));
		}

		// Return
		return new FilterResult(text, matchedWords.toArray(t -> new WordMatch[t]),
				messageParts.toArray(t -> new TextPart[t]),
				stringBuilder.buildOutputString(messageParts.toArray(t -> new TextPart[t])));
	}

}

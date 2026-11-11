package org.asf.centuria.tools;

import java.util.ArrayList;
import java.util.Scanner;

import org.asf.centuria.textfilter.FilterMode;
import org.asf.centuria.textfilter.FilterSeverity;
import org.asf.centuria.textfilter.PhraseFilter;
import org.asf.centuria.textfilter.PhraseFilterSet;
import org.asf.centuria.textfilter.TextFilterService;
import org.asf.centuria.textfilter.context.TextFilterContextMemory;
import org.asf.centuria.textfilter.result.FilterResult;
import org.asf.centuria.textfilter.result.TextPart;
import org.asf.centuria.textfilter.result.WordMatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JacksonException;

public class TextFilterTester {

	public static class ResultFilterSet {
		public String setName;
		public String setDescription;
		public String filterReason;

		public String[] tags;
	}

	public static class ResultPhraseFilter {

		public ResultFilterSet set;

		public String phrase;
		public String reason;

		public String[] variants;
		public FilterSeverity severity;
		public FilterMode[] modes;

	}

	public static class ResultTextPart {

		public String text;

		public boolean flagged;
		public FilterSeverity severity;
		public String reason;

		public ResultWordMatch[] matchedWords;

		public ResultPhraseFilter filter;

	}

	public static class ResultWordMatch {

		public ResultPhraseFilter filter;
		public FilterSeverity severity;
		public String reason;

		public String phrase;

		public String[] variants;

	}

	public static class ResultJson {

		public String originalText;
		public String filteredResult;
		public FilterSeverity resultSeverity;
		public String primaryReason = null;

		public ResultTextPart[] textParts;
		public ResultWordMatch[] matchedFilters;

		public long time;

	}

	private static ResultJson mapResult(FilterResult result) {
		ResultJson res = new ResultJson();
		res.originalText = result.getOriginalText();
		res.filteredResult = result.getFilterResult();
		res.primaryReason = result.getPrimaryFilterReason();
		res.resultSeverity = result.getSeverity();
		res.time = result.getResultTime();
		ArrayList<ResultWordMatch> matches = new ArrayList<ResultWordMatch>();
		for (WordMatch match : result.getMatches())
			matches.add(mapMatch(match));
		res.matchedFilters = matches.toArray(t -> new ResultWordMatch[t]);
		ArrayList<ResultTextPart> parts = new ArrayList<ResultTextPart>();
		for (TextPart part : result.getTextParts())
			parts.add(mapPart(part));
		res.textParts = parts.toArray(t -> new ResultTextPart[t]);

		return res;
	}

	private static ResultTextPart mapPart(TextPart part) {
		ResultTextPart res = new ResultTextPart();
		res.filter = mapFilter(part.getPhraseFilter());
		ArrayList<ResultWordMatch> matches = new ArrayList<ResultWordMatch>();
		for (WordMatch match : part.getMatchedWords())
			matches.add(mapMatch(match));
		res.matchedWords = matches.toArray(t -> new ResultWordMatch[t]);
		res.flagged = part.isFlagged();
		res.reason = part.getPrimaryFilteringReason();
		res.severity = part.getSeverity();
		res.text = part.getText();
		return res;
	}

	private static ResultWordMatch mapMatch(WordMatch match) {
		ResultWordMatch res = new ResultWordMatch();
		res.filter = mapFilter(match.getPhraseFilter());
		res.phrase = match.getMatchedPhrase();
		res.reason = match.getReason();
		res.severity = match.getSeverity();
		res.variants = match.getVariants();
		return res;
	}

	private static ResultPhraseFilter mapFilter(PhraseFilter filter) {
		if (filter == null)
			return null;
		ResultPhraseFilter res = new ResultPhraseFilter();
		res.modes = filter.getModes();
		res.phrase = filter.getPhrase();
		res.reason = filter.getReason();
		res.set = mapSet(filter.getSet());
		res.severity = filter.getSeverity();
		res.variants = filter.getVariants();
		return res;
	}

	private static ResultFilterSet mapSet(PhraseFilterSet set) {
		ResultFilterSet res = new ResultFilterSet();
		res.filterReason = set.getFilteringReason();
		res.setDescription = set.getSetDescription();
		res.setName = set.getSetName();
		res.tags = set.getSetTags();
		return res;
	}

	public static void main(String[] args) throws JacksonException {
		Scanner sc = new Scanner(System.in);
		System.out.print("Strictmode: ");
		TextFilterContextMemory mem = new TextFilterContextMemory(FilterSeverity.USER_STRICT_MODE, 10);
		String mode = sc.nextLine();
		if (!mode.equalsIgnoreCase("true") && !mode.equalsIgnoreCase("false")) {
			System.err.println("Error: invalid mode");
			System.exit(1);
			sc.close();
			return;
		}
		boolean strict = mode.equalsIgnoreCase("true");
		System.out.print("Tags: ");
		String[] tags = sc.nextLine().replace(" ", "").split(",");
		System.out.println("Initializing...");
		TextFilterService.getInstance().initService();
		System.out.println("Ready, enter any prompt to run through the filter to test the filters!");
		ObjectMapper mapper = new ObjectMapper();
		while (true) {
			System.out.print("> ");
			String input = sc.nextLine();
			FilterResult res = TextFilterService.getInstance().filter(mem, input, strict, tags);
			System.out.println(res.getFilterResult());
			mem.pushToContext(res);
			System.out.println("Result: " + mapper.writer().writeValueAsString(mapResult(res)));
			System.out.println("Memory: " + mapper.writer().writeValueAsString(mapMemory(mem)));
		}
	}

	public static class MemoryJson {
		public String[] messages;
	}

	private static Object mapMemory(TextFilterContextMemory mem) {
		MemoryJson res = new MemoryJson();
		ArrayList<String> messages = new ArrayList<String>();
		for (FilterResult msg : mem.getMessagesInContext()) {
			messages.add(msg.getFilterResult());
		}
		res.messages = messages.toArray(t -> new String[t]);
		return res;
	}

}

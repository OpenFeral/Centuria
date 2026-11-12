package org.asf.centuria.textfilter.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.events.textfilter.TextFilterLoadEvent;
import org.asf.centuria.textfilter.FilterSeverity;
import org.asf.centuria.textfilter.IFilterDefParser;
import org.asf.centuria.textfilter.IFilterRunner;
import org.asf.centuria.textfilter.IResultStringBuilder;
import org.asf.centuria.textfilter.PhraseFilterSet;
import org.asf.centuria.textfilter.TextFilterService;
import org.asf.centuria.textfilter.context.TextFilterContextMemory;
import org.asf.centuria.textfilter.impl.defparsers.DefaultFilterDefParser;
import org.asf.centuria.textfilter.impl.filterrunners.DefaultFilterRunner;
import org.asf.centuria.textfilter.impl.stringbuilders.DefaultStringBuilder;
import org.asf.centuria.textfilter.result.FilterResult;

public abstract class BaseTextFilterServiceImpl extends TextFilterService {

	private Map<String, PhraseFilterSet> filters = new HashMap<String, PhraseFilterSet>();

	public static IFilterDefParser parserImplementation = new DefaultFilterDefParser();
	public static IFilterRunner runnerImplementation = new DefaultFilterRunner();
	public static IResultStringBuilder stringBuilderImplementation = new DefaultStringBuilder();

	@Override
	public void initService() {
		// Load
		LogManager.getLogger().info("Initializing textfilter service... Loading filters...");
		loadFilters();
	}

	/**
	 * Called to load or reload filters
	 */
	protected abstract void loadFilters();

	/**
	 * Applies the list of loaded filters and calls the textfilter load event
	 * 
	 * @param filters Filter list to apply
	 */
	protected void applyFilters(Map<String, PhraseFilterSet> filters) {
		// Apply
		this.filters = filters;

		// Call load event
		EventBus.getInstance().dispatchEvent(new TextFilterLoadEvent(this));
	}

	/**
	 * Loads a filter set from a stream
	 * 
	 * @param filterFile Filter file stream, note: this does not close the stream,
	 *                   and only reads from it
	 * @param filePath   Filter file path used in errors
	 * @return PhraseFilterSet instance
	 * @throws IOException If loading the filter fails
	 */
	protected PhraseFilterSet loadFilter(InputStream filterFile, String filePath) throws IOException {
		return parserImplementation.loadFilter(filterFile, filePath);
	}

	@Override
	public PhraseFilterSet[] getFilterSets() {
		return filters.values().toArray(t -> new PhraseFilterSet[t]);
	}

	@Override
	public PhraseFilterSet getFilterSet(String name) {
		return filters.get(name.toLowerCase());
	}

	@Override
	public void addFilterSet(PhraseFilterSet set) {
		// Check
		if (filters.containsKey(set.getSetName().toLowerCase()))
			throw new IllegalArgumentException("Filter with name '" + set.getSetName() + "' already exists");
		filters.put(set.getSetName().toLowerCase(), set);
	}

	@Override
	public void reload() {
		loadFilters();
	}

	@Override
	protected IResultStringBuilder defaultStringBuilder() {
		return stringBuilderImplementation;
	}

	@Override
	public boolean isFiltered(TextFilterContextMemory memory, FilterSeverity minimalSeverity, String text,
			boolean strictMode, String... tags) {
		return runnerImplementation.isFiltered(memory, minimalSeverity, text, strictMode, filters, tags);
	}

	@Override
	public boolean shouldFilterMute(TextFilterContextMemory memory, FilterSeverity minimalSeverity, String text,
			String... tags) {
		return runnerImplementation.shouldFilterMute(memory, minimalSeverity, text, filters, tags);
	}

	@Override
	public FilterResult filter(TextFilterContextMemory memory, FilterSeverity minimalSeverity, String text,
			boolean strictMode, IResultStringBuilder stringBuilder, String... tags) {
		return runnerImplementation.filter(memory, minimalSeverity, text, strictMode, filters, tags, stringBuilder);
	}

}

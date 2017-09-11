package com.ooyala.jenkins.plugins.pipelineaggregatorview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.servlet.ServletException;

import hudson.Extension;
import hudson.model.Api;
import hudson.model.Item;
import hudson.model.ListView;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.ViewDescriptor;
import hudson.scm.ChangeLogSet;
import hudson.security.Permission;
import hudson.util.RunList;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * Created by paul on 20/09/16.
 */
@SuppressWarnings("unused")
@ExportedBean
public class PipelineAggregator extends View {

	private String viewName;

	private int fontSize;

	private int buildHistorySize;

	private boolean useCondensedTables;

	public boolean isUseScrollingCommits() {
		return useScrollingCommits;
	}

	public void setUseScrollingCommits(boolean useScrollingCommits) {
		this.useScrollingCommits = useScrollingCommits;
	}

	private boolean useScrollingCommits;

	private String filterRegex;

	private String paramsList;

	@DataBoundConstructor
	public PipelineAggregator(String name, String viewName) {
		super(name);
		this.viewName = viewName;
		this.fontSize = 16;
		this.buildHistorySize = 16;
		this.useCondensedTables = false;
		this.filterRegex = null;
		this.paramsList = null;
	}

	protected Object readResolve() {
		if (fontSize == 0) {
			fontSize = 16;
		}
		if (buildHistorySize == 0) {
			buildHistorySize = 16;
		}
		return this;
	}

	@Override
	public Collection<TopLevelItem> getItems() {
		return new ArrayList<TopLevelItem>();
	}

	public int getFontSize() {
		return fontSize;
	}

	public int getBuildHistorySize() {
		return buildHistorySize;
	}

	public boolean isUseCondensedTables() {
		return useCondensedTables;
	}

	public String getTableStyle() {
		return useCondensedTables ? "table-condensed" : "";
	}

	public String getFilterRegex() {
		return filterRegex;
	}

	public String getParamsList() {
		return paramsList;
	}

	@Override
	protected void submit(StaplerRequest req) throws ServletException, IOException {
		JSONObject json = req.getSubmittedForm();
		this.fontSize = json.getInt("fontSize");
		this.buildHistorySize = json.getInt("buildHistorySize");
		this.useCondensedTables = json.getBoolean("useCondensedTables");
		this.useScrollingCommits = json.getBoolean("useScrollingCommits");
		this.paramsList = req.getParameter("paramsList");
		if (json.get("useRegexFilter") != null) {
			String regexToTest = req.getParameter("filterRegex");
			try {
				Pattern.compile(regexToTest);
				this.filterRegex = regexToTest;
			}
			catch (PatternSyntaxException x) {
				Logger.getLogger(ListView.class.getName()).log(Level.WARNING, "Regex filter expression is invalid", x);
			}
		}
		else {
			this.filterRegex = null;
		}
		save();
	}

	@Override
	public Item doCreateItem(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
		return Jenkins.getInstance().doCreateItem(req, rsp);
	}

	@Override
	public boolean contains(TopLevelItem item) {
		return false;
	}

	@Override
	public boolean hasPermission(final Permission p) {
		return true;
	}

	/**
	 * This descriptor class is required to configure the View Page
	 */
	@Extension
	public static final class DescriptorImpl extends ViewDescriptor {

		@Override
		public String getDisplayName() {
			return
					"Pipeline Aggregator View";
		}
	}

	public Api getApi() {
		return new Api(this);
	}

	@Exported(name = "builds")
	public Collection<Build> getBuildHistory() {
		Jenkins jenkins = Jenkins.getInstance();
		List<WorkflowJob> jobs = jenkins.getAllItems(WorkflowJob.class);
		Pattern r = filterRegex != null ? Pattern.compile(filterRegex) : null;
		final String[] params = StringUtils.split(paramsList, ";");
		final Map<String, String> paramsMap = new HashMap<>();
		for (final String param : params) {
			final String[] kvs = StringUtils.split(param, "=");
			if (kvs.length != 2) {
				throw new IllegalArgumentException(String.format("%s is not a valid param def", param));
			}
			paramsMap.put(kvs[0], kvs[1]);
		}
		List<WorkflowRun> fRuns = filterRuns(jobs, r, paramsMap);
		List<Build> l = new ArrayList();for (WorkflowRun build : fRuns) {
			List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeLogSets = ((WorkflowRun) build).getChangeSets();
			Result result = build.getResult();
			l.add(new Build(build.getDisplayName(),
					build.getFullDisplayName(),
					build.getUrl(),
					build.getNumber(),
					build.getStartTimeInMillis(),
					build.getDuration(),
					result == null ? "BUILDING" : result.toString(), changeLogSets));
		}
		return l;
	}

	public List<WorkflowJob> filterJobs(List<WorkflowJob> jobs, Pattern r) {
		if (r == null) {
			return jobs;
		}
		for (Iterator<WorkflowJob> iterator = jobs.iterator(); iterator.hasNext(); ) {
			WorkflowJob job = iterator.next();
			WorkflowRun run = job.getLastBuild();
			if (run != null) {
				if (!r.matcher(run.getFullDisplayName()).find()) {
					iterator.remove();
				}
			}
			else {
				iterator.remove();
			}
		}
		return jobs;
	}

	public List<WorkflowRun> filterRuns(List<WorkflowJob> jobs, Pattern r, Map<String, String> paramsMap) {
		List<WorkflowRun> runList = new ArrayList<>();
		for (final WorkflowJob job : jobs) {
			final RunList runs = job.getNewBuilds(); // Only fetch the latest 100 runs
			boolean runFound = false; // If a run matching search criteria has found for this job
			if (runs != null) {
				for (Iterator<? extends Run> itr = runs.iterator(); itr.hasNext(); ) {
					if (runFound) {
						break;
					}
					final WorkflowRun run = (WorkflowRun) itr.next();
					if (r != null && !r.matcher(run.getFullDisplayName()).find()) {
						break;
					}
					if(run.getResult().isWorseOrEqualTo(Result.NOT_BUILT)) {
						continue;
					}
					if(paramsMap.isEmpty()) {
						runList.add(run);
						break;
					}
					final ParametersAction paramAction = run.getAction(ParametersAction.class);
					final List<ParameterValue> values = paramAction == null ? null : paramAction.getAllParameters();
					if (values != null) {
						for (final ParameterValue value : values) {
							if (paramsMap.containsKey(value.getName().toString()) && StringUtils.equals(paramsMap.get(value.getName().toString()), Objects.toString(value.getValue()))) {
								runFound = true;
								runList.add(run);
								break;
							}
						}
					}
				}
			}
		}
		return runList;
	}


	@ExportedBean(defaultVisibility = 999)
	public static class Build {

		@Exported
		public String jobName;

		@Exported
		public String buildName;

		@Exported
		public String url;

		@Exported
		public int number;

		@Exported
		public long startTime;

		@Exported
		public long duration;

		@Exported
		public String result;

		@Exported
		public List<ChangeLog> changeLogSet;

		public Build(String jobName, String buildName, String url, int number, long startTime, long duration, String result, List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeLogSets) {
			this.jobName = jobName;
			this.buildName = buildName;
			this.number = number;
			this.startTime = startTime;
			this.duration = duration;
			this.result = result;
			this.url = url;

			this.changeLogSet = processChanges(changeLogSets);
		}

		private List<ChangeLog> processChanges(List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeLogSets) {
			List<ChangeLog> changes = new ArrayList<>();
			for (ChangeLogSet<? extends ChangeLogSet.Entry> set : changeLogSets) {
				for (Object entry : set.getItems()) {
					ChangeLogSet.Entry setEntry = (ChangeLogSet.Entry) entry;
					String author = setEntry.getAuthor().getFullName();
					String message = setEntry.getMsg();
					changes.add(new ChangeLog(author, message));
				}

			}
			return changes;
		}
	}

	@ExportedBean(defaultVisibility = 999)
	public static class ChangeLog {

		@Exported
		public String author;

		@Exported
		public String message;

		public ChangeLog(String author, String message) {
			this.author = author;
			this.message = message;
		}
	}
}


/*
 * 
 * Copyright (c) 2016 Michael Epstein mikee805@aol.com
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * 
 * 
*/

package org.jenkinsci.plugins.elasticsearchquery;
import static hudson.util.FormValidation.error;
import static hudson.util.FormValidation.ok;
import static java.lang.Long.parseLong;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.lang.StringUtils.deleteWhitespace;
import static org.apache.commons.lang.StringUtils.endsWith;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.commons.lang.StringUtils.replace;
import static org.apache.commons.lang.StringUtils.trim;
import static org.apache.commons.lang.math.NumberUtils.isNumber;
import static org.apache.commons.lang.math.NumberUtils.toInt;
import static org.apache.commons.lang.time.DateUtils.addDays;
import static org.apache.http.params.HttpConnectionParams.setSoTimeout;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;

import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.time.FastDateFormat;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Simple builder that queries elasticsearch to use for downstream notifications
 *
 * @author Michael Epstein 
 */
public class ElasticsearchQueryBuilder extends Builder implements SimpleBuildStep {
	
    private final static FastDateFormat LOGSTASH_INDEX_FORMAT = FastDateFormat.getInstance("yyyy.MM.dd"); 
    private final static String LOGSTASH_INDEX_PREFIX = "logstash-";

    private final String query;
    private final String aboveOrBelow;
    private final Long threshold;
    private final Long since;
    private final String units;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public ElasticsearchQueryBuilder(String query, String aboveOrBelow, Long threshold, Long since, String units) {
        this.query = trim(query);
        this.aboveOrBelow = trim(aboveOrBelow);
        this.threshold = threshold;
        this.since = since;
        this.units = units;
    }

	public String getQuery() {
		return query;
	}

	public String getAboveOrBelow() {
		return aboveOrBelow;
	}

	public Long getThreshold() {
		return threshold;
	}

	public Long getSince() {
		return since;
	}

	public String getUnits() {
		return units;
	}
	
	private String buildLogstashIndexes(final long past){
		final StringBuilder stringBuilder = new StringBuilder();
		final String pastDateString = LOGSTASH_INDEX_FORMAT.format(new Date(past));
		Date currentDate = new Date();
		String currentDateString = LOGSTASH_INDEX_FORMAT.format(currentDate);
		stringBuilder.append(LOGSTASH_INDEX_PREFIX);
		stringBuilder.append(currentDateString);
		while(!currentDateString.equals(pastDateString)){
			currentDate = addDays(currentDate, -1);
			currentDateString = LOGSTASH_INDEX_FORMAT.format(currentDate);
			stringBuilder.append(",");
			stringBuilder.append(LOGSTASH_INDEX_PREFIX);
			stringBuilder.append(currentDateString);
		}
		
		return stringBuilder.toString();
	}

	@Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws AbortException {
		//print our arguments
		listener.getLogger().println("Query: " + query);
		listener.getLogger().println("Fail when: " + aboveOrBelow);
		listener.getLogger().println("Threshold: " + threshold);
		listener.getLogger().println("Since: " + since);
		listener.getLogger().println("Time units: " + units);
		//get
		final String user = getDescriptor().getUser();
		//get
		final String password = getDescriptor().getPassword();
		//validate global user and password config
		if(isEmpty(user) != isEmpty(password)){
			throw new AbortException("user and password must both be provided or empty! Please set value of user and password in Jenkins > Manage Jenkins > Configure System > Elasticsearch Query Builder");
		}
		
		final String creds = isEmpty(user) ? "" : user + ":" + password + "@";

		//get
		final String host = getDescriptor().getHost();
		//print
		listener.getLogger().println("host: " + host);
		//validate global host config
		if(isEmpty(host)){
			throw new AbortException("Host cannot be empty! Please set value of host in Jenkins > Manage Jenkins > Configure System > ElasticSearch Query Builder");
		}
        
		//Calculate time in past for search and indexes
        Long past = currentTimeMillis() - MILLISECONDS.convert(since, TimeUnit.valueOf(units));
        
        //create the date clause to be added the query to restrict by relative time
        final String dateClause = " AND @timestamp:>="+past;
        //use past to calculate specific indexes to search similar to kibana 3
        //ie if we are looking back to yesterday we dont need to search every index 
        //only today and yesterday
        final String queryIndexes = isNotBlank(getDescriptor().getIndexes()) ? getDescriptor().getIndexes() : buildLogstashIndexes(past);
        listener.getLogger().println("queryIndexes: " + queryIndexes);
        
        //we have all the parts now build the query URL
        String url = null;
		try {
			url = "http" + (getDescriptor().getUseSSL() ? "s" : "") + "://" + creds + getDescriptor().getHost() + "/" + queryIndexes + "/_count?pretty=true&q=" + new URLCodec().encode(query + dateClause);
		} catch (EncoderException ee) {
			throw new RuntimeException(ee);
		}
		listener.getLogger().println("query url: " + url);

        HttpClient httpClient = new DefaultHttpClient();
        final HttpGet httpget = new HttpGet(url);
        final Integer queryRequestTimeout = getDescriptor().getQueryRequestTimeout();
        setSoTimeout(httpClient.getParams(), queryRequestTimeout == null || queryRequestTimeout < 1 ? getDescriptor().defaultQueryRequestTimeout() : queryRequestTimeout);
        HttpResponse response = null;

		try {
			response = httpClient.execute(httpget);
			listener.getLogger().println("response: " + response);
		} catch (ClientProtocolException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
        HttpEntity entity = response.getEntity();
        
         if (entity != null) {
        	 String content = null;
        	 Long count = null;
        	 InputStream instream = null;
        	      try {
        	    	  
        	    	  instream = entity.getContent();

        	          // do something useful with the response
        	          content = IOUtils.toString(instream);
        	          listener.getLogger().println("content: " + content);
        	          Map<String, Object> map = new Gson().fromJson(content, new TypeToken<Map<String, Object>>() {}.getType());
        	          listener.getLogger().println("count: " + map.get("count"));
        	          count = Math.round((Double) map.get("count"));
        	 
        	      } catch (Exception ex) {
        	 
        	          // In case of an unexpected exception you may want to abort
        	          // the HTTP request in order to shut down the underlying
        	          // connection and release it back to the connection manager.
        	          httpget.abort();
        	          throw new RuntimeException(ex);
        	 
        	      } finally {
        	 
        	          // Closing the input stream will trigger connection release
						closeQuietly(instream);
        	 
        	      }
        	      
        	      listener.getLogger().println("search url: " + replace(url, "_count", "_search"));
        	      
        	      final String abortMessage = threshold +". Failing build!\n"
  	    		  		+ "URL: " + url +"\n"
  	    		  		+ "response content: " + content;
        	      if(aboveOrBelow.equals("gte")){
        	    	  if(count >= threshold){
        	    		  throw new AbortException("Count: " + count + " is >= " + abortMessage);
        	    	  }
        	      }else{
        	    	  if(count <= threshold){
        	    		  throw new AbortException("Count: " + count + " is <= " + abortMessage);
        	    	  } 
        	      }
         }
    }

    // Overridden for better type safety.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link ElasticsearchQueryBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */
        private String host;
        private String indexes;
        private String user;
        private String password;
        private boolean useSSL;
        private Integer queryRequestTimeout;
        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'query'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckQuery(@QueryParameter String value)
                throws IOException, ServletException {
            if (isBlank(value))
                return FormValidation.error("Please set a query");
            return ok();
        }
        
        public FormValidation doCheckIndexes(@QueryParameter String value)
                throws IOException, ServletException {
            if (isNotBlank(value)){
            	if(!deleteWhitespace(value).equals(value)){
            		return error("Indexes cannot contain whitespace");
            	}
            	if(endsWith(value, ",")){
            		return error("Indexes cannot end with a comma");
            	}
            }
            return ok();
        }
        
        public FormValidation doCheckThreshold(@QueryParameter String value)
                throws IOException, ServletException {
        	value = trim(value);
            if (!isNumber(value) || parseLong(value) < 0)
                return FormValidation.error("Please set a threshold greater than or equal to 0");
            return FormValidation.ok();
        }
        
        public FormValidation doCheckSince(@QueryParameter Long value)
                throws IOException, ServletException {
            if (value == null || value < 1)
                return FormValidation.error("Please set a since value greater than 0");
            return FormValidation.ok();
        }
        
        public FormValidation doCheckQueryRequestTimeout(@QueryParameter Integer value)
                throws IOException, ServletException {
            if (value == null || value < 1)
                return FormValidation.error("Please set a value greater than 0");
            return FormValidation.ok();
        }

        public boolean isApplicable(@SuppressWarnings("rawtypes") Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }
        
        public ListBoxModel doFillAboveOrBelowItems() {
            ListBoxModel items = new ListBoxModel();
        	items.add("gte");
        	items.add("lte");
            return items;
        }
        
        public ListBoxModel doFillUnitsItems() {
            ListBoxModel items = new ListBoxModel();
        	items.add(MINUTES.name());
        	items.add(HOURS.name());
        	items.add(DAYS.name());
            return items;
        }
        
        public Integer defaultQueryRequestTimeout() {
        	return 120000;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Elasticsearch Query";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
        	host = trim(formData.getString("host"));
        	indexes = trim(formData.getString("indexes"));
        	user = formData.getString("user");
        	password = trim(formData.getString("password"));
        	useSSL = formData.getBoolean("useSSL");
        	queryRequestTimeout = toInt(trim(formData.getString("queryRequestTimeout")), defaultQueryRequestTimeout());
        	
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req,formData);
        }

        public String getHost() {
            return host;
        }

		public String getIndexes() {
			return indexes;
		}

		public String getUser() {
			return user;
		}

		public String getPassword() {
			return password;
		}

		public boolean getUseSSL() {
			return useSSL;
		}

		public Integer getQueryRequestTimeout() {
			return queryRequestTimeout;
		}
        
    }
}


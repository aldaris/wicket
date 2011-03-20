/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.wicket.protocol.http;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.ParserConfigurationException;

import junit.framework.TestCase;

import org.apache.wicket.Application;
import org.apache.wicket.ThreadContext;
import org.apache.wicket.mock.MockApplication;
import org.apache.wicket.protocol.http.mock.MockHttpServletRequest;
import org.apache.wicket.protocol.http.mock.MockHttpServletResponse;
import org.apache.wicket.protocol.http.mock.MockServletContext;
import org.apache.wicket.request.resource.DynamicImageResource;
import org.apache.wicket.util.file.WebXmlFile;
import org.xml.sax.SAXException;

public class WicketFilterTest extends TestCase
{
	private static WebApplication application;
	private final DateFormat headerDateFormat = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z",
		Locale.UK);

	@Override
	protected void tearDown() throws Exception
	{
		application = null;
	}

	public void testFilterPath1()
	{
		InputStream in = WicketFilterTest.class.getResourceAsStream("web1.xml");
		String filterPath = getFilterPath("FilterTestApplication", in);
		assertEquals("filtertest/", filterPath);
	}

	public void testFilterPath2()
	{
		InputStream in = WicketFilterTest.class.getResourceAsStream("web2.xml");
		String filterPath = getFilterPath("FilterTestApplication", in);
		assertEquals("filtertest/", filterPath);
	}

	public void testNotModifiedResponseIncludesExpiresHeader() throws IOException,
		ServletException, ParseException
	{
		try
		{
			application = new MockApplication();
			WicketFilter filter = new WicketFilter();
			filter.init(new FilterTestingConfig());
			ThreadContext.setApplication(application);
			DynamicImageResource resource = new DynamicImageResource()
			{
				private static final long serialVersionUID = 1L;

				@Override
				protected byte[] getImageData(Attributes attributes)
				{
					throw new UnsupportedOperationException("Not implemented");
				}

				@Override
				protected ResourceResponse newResourceResponse(Attributes attributes)
				{
					ResourceResponse response = super.newResourceResponse(attributes);
					response.setCacheDurationToMaximum();
					return response;
				}
			};
			application.getSharedResources().add("foo.gif", resource);
			MockHttpServletRequest request = new MockHttpServletRequest(application, null, null);
			request.setURL(request.getContextPath() + request.getServletPath() +
				"/wicket/resource/" + Application.class.getName() + "/foo.gif");
			setIfModifiedSinceToNextWeek(request);
			MockHttpServletResponse response = new MockHttpServletResponse(request);
			filter.doFilter(request, response, new FilterChain()
			{
				public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse)
					throws IOException, ServletException
				{
				}
			});
			assertEquals(HttpServletResponse.SC_NOT_MODIFIED, response.getStatus());
			String responseExpiresHeader = response.getHeader("Expires");
			assertNotNull("Expires header must be set on not modified response",
				responseExpiresHeader);

			Date responseExpires = headerDateFormat.parse(responseExpiresHeader);
			assertTrue("Expected later than current date but was " + responseExpires,
				responseExpires.after(new Date()));
		}
		finally
		{
			ThreadContext.detach();
		}
	}

	private void setIfModifiedSinceToNextWeek(MockHttpServletRequest request)
	{
		Calendar nextWeek = Calendar.getInstance();
		nextWeek.add(Calendar.DATE, 7);
		nextWeek.setTimeZone(TimeZone.getTimeZone("GMT"));
		request.addDateHeader("If-Modified-Since", nextWeek.getTimeInMillis());
	}

	private String getFilterPath(String filterName, InputStream in)
	{
		try
		{
			return new WebXmlFile().getUniqueFilterPath(false, filterName, in);
		}
		catch (ParserConfigurationException ex)
		{
			throw new RuntimeException(ex);
		}
		catch (SAXException ex)
		{
			throw new RuntimeException(ex);
		}
		catch (IOException ex)
		{
			throw new RuntimeException(ex);
		}
	}

	private static class FilterTestingConfig implements FilterConfig
	{
		private final Map<String, String> initParameters = new HashMap<String, String>();

		public FilterTestingConfig()
		{
			initParameters.put(WicketFilter.APP_FACT_PARAM,
				FilterTestingApplicationFactory.class.getName());
			initParameters.put(WicketFilter.FILTER_MAPPING_PARAM, "/servlet/*");
			initParameters.put(ContextParamWebApplicationFactory.APP_CLASS_PARAM,
				MockApplication.class.getName());
		}

		public String getFilterName()
		{
			return getClass().getName();
		}

		public ServletContext getServletContext()
		{
			return new MockServletContext(null, null);
		}

		public String getInitParameter(String s)
		{
			return initParameters.get(s);
		}

		public Enumeration<String> getInitParameterNames()
		{
			throw new UnsupportedOperationException("Not implemented");
		}
	}

	public static class FilterTestingApplicationFactory implements IWebApplicationFactory
	{
		public WebApplication createApplication(WicketFilter filter)
		{
			return application;
		}

		/** {@inheritDoc} */
		public void destroy(WicketFilter filter)
		{
		}
	}

	public void testCheckRedirect_1()
	{
		WicketFilter filter = new WicketFilter();

		// Simulate url-pattern = "/*" and request = http://localhost:8080 => null == no redirect
		filter.setFilterPath("");
		assertNull("", filter.checkIfRedirectRequired("/", ""));
	}

	private static class CheckRedirectWorker implements Runnable
	{
		private final WicketFilter filter;
		private final CountDownLatch startLatch;
		private final CountDownLatch finishLatch;
		private final AtomicInteger successCount;

		public CheckRedirectWorker(WicketFilter filter, CountDownLatch startLatch,
			CountDownLatch finishLatch, AtomicInteger successCount)
		{
			this.filter = filter;
			this.startLatch = startLatch;
			this.finishLatch = finishLatch;
			this.successCount = successCount;
		}

		public void run()
		{
			try
			{
				try
				{
					startLatch.await(2, TimeUnit.SECONDS);
				}
				catch (InterruptedException e)
				{
					fail();
				}
				assertEquals("/filter/", filter.checkIfRedirectRequired("/filter", ""));
				successCount.incrementAndGet();
			}
			finally
			{
				finishLatch.countDown();
			}
		}
	}

	/**
	 * Starts {@code threadCount} threads which try to check whether a redirect is required and
	 * initialize {@link WicketFilter#filterPathLength}
	 * 
	 * @param threadCount
	 *            the number of simultaneous threads
	 */
	private void testParallelCheckRedirect(int threadCount)
	{
		WicketFilter filter = new WicketFilter();
		filter.setFilterPath("filter/");
		AtomicInteger successCount = new AtomicInteger(0);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch finishLatch = new CountDownLatch(threadCount);
		for (int i = 0; i < threadCount; i++)
		{
			new Thread(new CheckRedirectWorker(filter, startLatch, finishLatch, successCount)).start();
		}
		startLatch.countDown();
		try
		{
			finishLatch.await(2, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			fail();
		}
		assertEquals("all threads finished", 0, finishLatch.getCount());
		assertEquals("all redirects correct", threadCount, successCount.get());
	}

	/**
	 * <a href="https://issues.apache.org/jira/browse/WICKET-3544">WICKET-3544</a>
	 * <p>
	 * Runs 1000 times 8 simultaneous threads which try to initialize WicketFilter#filterPathLength
	 */
	public void testRepeatedParallelCheckRedirect()
	{
		int threadCount = 8;
		int repeatCount = 1000;
		for (int i = 0; i < repeatCount; i++)
		{
			testParallelCheckRedirect(threadCount);
		}
	}
}

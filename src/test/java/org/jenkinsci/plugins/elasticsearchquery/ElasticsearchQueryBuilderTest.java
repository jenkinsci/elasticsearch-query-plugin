package org.jenkinsci.plugins.elasticsearchquery;

import static hudson.util.FormValidation.Kind.ERROR;
import static hudson.util.FormValidation.Kind.OK;
import static org.junit.Assert.assertEquals;
import static org.powermock.api.support.membermodification.MemberMatcher.constructor;
import static org.powermock.api.support.membermodification.MemberModifier.suppress;

import org.jenkinsci.plugins.elasticsearchquery.ElasticsearchQueryBuilder.DescriptorImpl;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest(ElasticsearchQueryBuilder.DescriptorImpl.class)
public class ElasticsearchQueryBuilderTest {
	
	@Before
	public void init() {
		suppress(constructor(ElasticsearchQueryBuilder.DescriptorImpl.class));
	}
		
	@Test
	public void testDoCheckQuery() throws Exception {
		assertEquals(ERROR, new DescriptorImpl().doCheckQuery("").kind);
		assertEquals(OK, new DescriptorImpl().doCheckQuery("q").kind);
	}
	
	@Test
	public void testDoCheckIndexes() throws Exception {
		assertEquals(ERROR, new DescriptorImpl().doCheckIndexes(", ,").kind);
		assertEquals(ERROR, new DescriptorImpl().doCheckIndexes(",").kind);
		assertEquals(OK, new DescriptorImpl().doCheckIndexes("q").kind);
		assertEquals(OK, new DescriptorImpl().doCheckIndexes("").kind);
	}
	
	@Test
	public void testDoCheckSince() throws Exception {
		assertEquals(ERROR, new DescriptorImpl().doCheckSince(null).kind);
		assertEquals(OK, new DescriptorImpl().doCheckSince(0L).kind);
	}
	
	@Test
	public void testDoCheckThreshold() throws Exception {
		assertEquals(ERROR, new DescriptorImpl().doCheckThreshold(null).kind);
		assertEquals(OK, new DescriptorImpl().doCheckThreshold(0L).kind);
	}
}

/*
' * (C) Copyright 2012 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.platform.routing.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.nuxeo.ecm.platform.routing.api.DocumentRoutingConstants.WORKFLOW_FORCE_RESUME;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationChain;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.ClientRuntimeException;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.impl.UserPrincipal;
import org.nuxeo.ecm.core.api.model.PropertyException;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.ecm.core.test.TransactionalFeature;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.platform.routing.api.DocumentRoute;
import org.nuxeo.ecm.platform.routing.api.DocumentRoutingService;
import org.nuxeo.ecm.platform.routing.api.operation.BulkRestartWorkflow;
import org.nuxeo.ecm.platform.routing.core.impl.GraphNode;
import org.nuxeo.ecm.platform.routing.core.impl.GraphNode.State;
import org.nuxeo.ecm.platform.routing.core.impl.GraphNode.TaskInfo;
import org.nuxeo.ecm.platform.routing.core.impl.GraphRoute;
import org.nuxeo.ecm.platform.task.Task;
import org.nuxeo.ecm.platform.task.TaskService;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;
import org.nuxeo.runtime.test.runner.RuntimeHarness;

import com.google.inject.Inject;

@RunWith(FeaturesRunner.class)
@Features({ TransactionalFeature.class, CoreFeature.class,
        AutomationFeature.class })
@Deploy({
        "org.nuxeo.ecm.platform.content.template", //
        "org.nuxeo.ecm.automation.core", //
        "org.nuxeo.ecm.directory", //
        "org.nuxeo.ecm.platform.usermanager", //
        "org.nuxeo.ecm.directory.types.contrib", //
        "org.nuxeo.ecm.directory.sql", //
        "org.nuxeo.ecm.platform.userworkspace.core", //
        "org.nuxeo.ecm.platform.userworkspace.types", //
        "org.nuxeo.ecm.platform.task.api", //
        "org.nuxeo.ecm.platform.task.core", //
        "org.nuxeo.ecm.platform.task.testing",
        "org.nuxeo.ecm.platform.routing.api",
        "org.nuxeo.ecm.platform.routing.core" //
})
@LocalDeploy({
        "org.nuxeo.ecm.platform.routing.core:OSGI-INF/test-graph-operations-contrib.xml",
        "org.nuxeo.ecm.platform.routing.core:OSGI-INF/test-graph-types-contrib.xml" })
@RepositoryConfig(cleanup = Granularity.METHOD)
public class GraphRouteTest extends AbstractGraphRouteTest {

    protected static final String TYPE_ROUTE_NODE = "RouteNode";

    @Inject
    protected FeaturesRunner featuresRunner;

    @Inject
    protected RuntimeHarness harness;

    @Inject
    protected CoreSession session;

    @Inject
    protected DocumentRoutingService routing;

    // init userManager now for early user tables creation (cleaner debug)
    @Inject
    protected UserManager userManager;

    @Inject
    protected TaskService taskService;

    @Inject
    protected AutomationService automationService;

    @Before
    public void setUp() throws Exception {
        assertNotNull(routing);

        doc = session.createDocumentModel("/", "file", "File");
        doc.setPropertyValue("dc:title", "file");
        doc = session.createDocument(doc);

        routeDoc = createRoute("myroute", session);
    }

    @After
    public void tearDown() {
        // breakpoint here to examine database after test
    }

    protected CoreSession openSession(NuxeoPrincipal principal)
            throws ClientException {
        CoreFeature coreFeature = featuresRunner.getFeature(CoreFeature.class);
        Map<String, Serializable> ctx = new HashMap<String, Serializable>();
        ctx.put("principal", principal);
        return coreFeature.getRepository().getRepositoryHandler().openSession(
                ctx);
    }

    protected void closeSession(CoreSession session) {
        CoreInstance.getInstance().close(session);
    }

    protected Map<String, Serializable> keyvalue(String key, String value) {
        Map<String, Serializable> m = new HashMap<String, Serializable>();
        m.put(GraphNode.PROP_KEYVALUE_KEY, key);
        m.put(GraphNode.PROP_KEYVALUE_VALUE, value);
        return m;
    }

    protected void setSubRouteVariables(DocumentModel node,
            @SuppressWarnings("unchecked")
            Map<String, Serializable>... keyvalues) throws ClientException {
        node.setPropertyValue(GraphNode.PROP_SUB_ROUTE_VARS,
                (Serializable) Arrays.asList(keyvalues));
    }

    @Test
    public void testExceptionIfNoStartNode() throws Exception {
        // route
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1 = session.saveDocument(node1);
        try {
            instantiateAndRun(session);
            fail("Should throw because no start node");
        } catch (ClientRuntimeException e) {
            String msg = e.getMessage();
            assertTrue(msg, msg.contains("No start node for graph"));
        }
    }

    @Test
    public void testExceptionIfNoTrueTransition() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        node1 = session.saveDocument(node1);
        try {
            instantiateAndRun(session);
            fail("Should throw because no transition is true");
        } catch (ClientRuntimeException e) {
            String msg = e.getMessage();
            assertTrue(msg, msg.contains("No transition evaluated to true"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testExceptionIfTransitionIsNotBoolean() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1, transition("trans1", "node1", "'notaboolean'"));
        node1 = session.saveDocument(node1);
        try {
            instantiateAndRun(session);
            fail("Should throw because transition condition is no bool");
        } catch (ClientRuntimeException e) {
            String msg = e.getMessage();
            assertTrue(msg, msg.contains("does not evaluate to a boolean"));
        }
    }

    @Test
    public void testOneNodeStartStop() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node1 = session.saveDocument(node1);
        DocumentRoute route = instantiateAndRun(session);
        assertTrue(route.isDone());
    }

    @Test
    public void testStartWithMap() throws Exception {
        routeDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET,
                "FacetRoute1");
        routeDoc = session.saveDocument(routeDoc);
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node1 = session.saveDocument(node1);
        Map<String, Serializable> map = new HashMap<String, Serializable>();
        map.put("stringfield", "ABC");
        DocumentRoute route = instantiateAndRun(session,
                Collections.singletonList(doc.getId()), map);
        assertTrue(route.isDone());
        String v = (String) route.getDocument().getPropertyValue(
                "fctroute1:stringfield");
        assertEquals("ABC", v);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testExceptionIfLooping() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1, transition("trans1", "node1"));
        node1 = session.saveDocument(node1);
        try {
            instantiateAndRun(session);
            fail("Should throw because execution is looping");
        } catch (ClientRuntimeException e) {
            String msg = e.getMessage();
            assertTrue(msg, msg.contains("Execution is looping"));
        }
    }

    @Test
    public void testAutomationChains() throws Exception {
        assertEquals("file", doc.getTitle());
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_title1");
        node1.setPropertyValue(GraphNode.PROP_OUTPUT_CHAIN, "testchain_title2");
        node1 = session.saveDocument(node1);
        DocumentRoute route = instantiateAndRun(session);
        assertTrue(route.isDone());
        doc.refresh();
        assertEquals("title 2", doc.getTitle());
    }

    @Test
    public void testAutomationChainVariableChange() throws Exception {
        // route model var
        routeDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET,
                "FacetRoute1");
        routeDoc = session.saveDocument(routeDoc);
        // node model
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_INPUT_CHAIN,
                "testchain_stringfield");
        node1.setPropertyValue(GraphNode.PROP_OUTPUT_CHAIN,
                "testchain_stringfield2");
        // node model var
        node1.setPropertyValue(GraphNode.PROP_VARIABLES_FACET, "FacetNode1");
        node1 = session.saveDocument(node1);
        DocumentRoute route = instantiateAndRun(session);
        assertTrue(route.isDone());

        // check route instance var
        DocumentModel r = route.getDocument();
        String s = (String) r.getPropertyValue("fctroute1:stringfield");
        assertEquals("foo", s);
        // Calendar d = (Calendar) r.getPropertyValue("datefield");
        // assertEquals("XXX", d);

        // check node instance var
        // must be admin to get children, due to rights restrictions
        NuxeoPrincipal admin = new UserPrincipal("admin", null, false, true);
        CoreSession ses = openSession(admin);
        DocumentModel c = ses.getChildren(r.getRef()).get(0);
        s = (String) c.getPropertyValue("stringfield2");
        assertEquals("bar", s);
        closeSession(ses);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSimpleTransition() throws Exception {
        assertEquals("file", doc.getTitle());
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1,
                transition("trans1", "node2", "true", "testchain_title1"),
                transition("trans2", "node2", "false", "testchain_title2"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node2 = session.saveDocument(node2);

        DocumentRoute route = instantiateAndRun(session);

        assertTrue(route.isDone());
        doc.refresh();
        assertEquals("title 1", doc.getTitle());

        // check start/end dates and counts
        DocumentModel doc1 = ((GraphRoute) route).getNode("node1").getDocument();
        assertEquals(Long.valueOf(1), doc1.getPropertyValue("rnode:count"));
        assertNotNull(doc1.getPropertyValue("rnode:startDate"));
        assertNotNull(doc1.getPropertyValue("rnode:endDate"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testResume() throws Exception {
        assertEquals("file", doc.getTitle());
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1,
                transition("trans12", "node2", "true", "testchain_title1"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        setTransitions(node2, transition("trans23", "node3"));
        node2 = session.saveDocument(node2);

        DocumentModel node3 = createNode(routeDoc, "node3", session);
        node3.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node3 = session.saveDocument(node3);

        DocumentRoute route = instantiateAndRun(session);

        assertFalse(route.isDone());

        // now resume, as if the task was actually executed
        routing.resumeInstance(route.getDocument().getId(), "node2", null,
                null, session);

        route.getDocument().refresh();
        assertTrue(route.isDone());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testCancel() throws Exception {
        assertEquals("file", doc.getTitle());
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1,
                transition("trans12", "node2", "true", "testchain_title1"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        setTransitions(node2, transition("trans23", "node3"));
        node2 = session.saveDocument(node2);

        DocumentModel node3 = createNode(routeDoc, "node3", session);
        node3.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node3 = session.saveDocument(node3);

        DocumentRoute route = instantiateAndRun(session);

        assertFalse(route.isDone());

        List<Task> tasks = taskService.getTaskInstances(doc,
                (NuxeoPrincipal) null, session);
        assertEquals(1, tasks.size());

        route.cancel(session);
        route.getDocument().refresh();
        assertTrue(route.isCanceled());
        session.save();

        tasks = taskService.getTaskInstances(doc, (NuxeoPrincipal) null,
                session);
        assertEquals(0, tasks.size());
        DocumentModelList cancelledTasks = session.query("Select * from TaskDoc where ecm:currentLifeCycleState = 'cancelled'");
        assertEquals(1, cancelledTasks.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testForkMergeAll() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1,
                transition("trans1", "node2", "true", "testchain_title1"),
                transition("trans2", "node2", "true", "testchain_descr1"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");
        node2.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_rights1");
        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node2 = session.saveDocument(node2);

        DocumentRoute route = instantiateAndRun(session);

        assertTrue(route.isDone());
        doc.refresh();
        assertEquals("title 1", doc.getTitle());
        assertEquals("descr 1", doc.getPropertyValue("dc:description"));
        assertEquals("rights 1", doc.getPropertyValue("dc:rights"));
    }

    // a few more nodes before the merge
    @SuppressWarnings("unchecked")
    @Test
    public void testForkMergeAll2() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1, transition("trans1", "node2"),
                transition("trans2", "node3"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_title1");
        setTransitions(node2, transition("trans1", "node4"));
        node2 = session.saveDocument(node2);

        DocumentModel node3 = createNode(routeDoc, "node3", session);
        node3.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_descr1");
        setTransitions(node3, transition("trans2", "node4"));
        node3 = session.saveDocument(node3);

        DocumentModel node4 = createNode(routeDoc, "node4", session);
        node4.setPropertyValue(GraphNode.PROP_MERGE, "all");
        node4.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_rights1");
        node4.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node4 = session.saveDocument(node4);

        DocumentRoute route = instantiateAndRun(session);

        assertTrue(route.isDone());
        doc.refresh();
        assertEquals("title 1", doc.getTitle());
        assertEquals("descr 1", doc.getPropertyValue("dc:description"));
        assertEquals("rights 1", doc.getPropertyValue("dc:rights"));
    }

    // a few more nodes before the merge
    @SuppressWarnings("unchecked")
    @Test
    public void testForkMergeAllWithTasks() throws Exception {
        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        assertNotNull(user1);

        NuxeoPrincipal user2 = userManager.getPrincipal("myuser2");
        assertNotNull(user2);

        NuxeoPrincipal user3 = userManager.getPrincipal("myuser3");
        assertNotNull(user3);

        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1, transition("trans1", "node2"),
                transition("trans2", "node3"), transition("trans3", "node4"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_title1");
        setTransitions(node2, transition("trans1", "node5"));

        node2.setPropertyValue(GraphNode.PROP_OUTPUT_CHAIN, "testchain_rights1");
        node2.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES_PERMISSION,
                "Write");
        node2.setPropertyValue(GraphNode.PROP_INPUT_CHAIN,
                "test_setGlobalvariable");
        node2.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        String[] users = { user1.getName() };
        node2.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users);
        setButtons(node2, button("btn1", "label-btn1", "filterrr"));
        node2 = session.saveDocument(node2);

        DocumentModel node3 = createNode(routeDoc, "node3", session);
        node3.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_descr1");
        setTransitions(node3, transition("trans2", "node5"));

        node3.setPropertyValue(GraphNode.PROP_OUTPUT_CHAIN, "testchain_rights1");
        node3.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES_PERMISSION,
                "Write");
        node3.setPropertyValue(GraphNode.PROP_INPUT_CHAIN,
                "test_setGlobalvariable");
        node3.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        String[] users2 = { user2.getName() };
        node3.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users2);
        setButtons(node1, button("btn2", "label-btn2", "filterrr"));
        node3 = session.saveDocument(node3);

        DocumentModel node4 = createNode(routeDoc, "node4", session);
        node4.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_descr1");
        setTransitions(node4, transition("trans3", "node5"));

        node4.setPropertyValue(GraphNode.PROP_OUTPUT_CHAIN, "testchain_rights1");
        node4.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES_PERMISSION,
                "Write");
        node4.setPropertyValue(GraphNode.PROP_INPUT_CHAIN,
                "test_setGlobalvariable");
        node4.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        String[] users3 = { user3.getName() };
        node4.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users3);
        setButtons(node1, button("btn2", "label-btn2", "filterrr"));
        node4 = session.saveDocument(node4);

        DocumentModel node5 = createNode(routeDoc, "node5", session);
        node5.setPropertyValue(GraphNode.PROP_MERGE, "all");
        node5.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_rights1");
        node5.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node5 = session.saveDocument(node5);

        DocumentRoute route = instantiateAndRun(session);

        List<Task> tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        Map<String, Object> data = new HashMap<String, Object>();
        CoreSession sessionUser1 = openSession(user1);
        // task assignees have READ on the route instance
        assertNotNull(sessionUser1.getDocument(route.getDocument().getRef()));
        routing.endTask(sessionUser1, tasks.get(0), data, "trans1");
        closeSession(sessionUser1);

        tasks = taskService.getTaskInstances(doc, user2, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        data = new HashMap<String, Object>();
        CoreSession sessionUser2 = openSession(user2);
        // task assignees have READ on the route instance
        assertNotNull(sessionUser2.getDocument(route.getDocument().getRef()));
        routing.endTask(sessionUser2, tasks.get(0), data, "trans2");
        closeSession(sessionUser2);

        // end task and verify that route was done
        NuxeoPrincipal admin = new UserPrincipal("admin", null, false, true);
        session = openSession(admin);
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        assertFalse(route.isDone());

        tasks = taskService.getTaskInstances(doc, user3, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        data = new HashMap<String, Object>();
        CoreSession sessionUser3 = openSession(user3);
        // task assignees have READ on the route instance
        assertNotNull(sessionUser3.getDocument(route.getDocument().getRef()));
        routing.endTask(sessionUser3, tasks.get(0), data, "trans3");
        closeSession(sessionUser3);

        // end task and verify that route was done
        admin = new UserPrincipal("admin", null, false, true);
        closeSession(session);
        session = openSession(admin);
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        assertTrue(route.isDone());
        closeSession(session);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testForkMergeOne() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1, transition("trans12", "node2"),
                transition("trans13", "node3"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_title1");
        setTransitions(node2, transition("trans25", "node5"));
        node2 = session.saveDocument(node2);

        DocumentModel node3 = createNode(routeDoc, "node3", session);
        node3.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_descr1");
        setTransitions(node3, transition("trans34", "node4"));
        node3 = session.saveDocument(node3);

        DocumentModel node4 = createNode(routeDoc, "node4", session);
        node4.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_descr2");
        setTransitions(node4, transition("trans45", "node5"));
        node4 = session.saveDocument(node4);

        DocumentModel node5 = createNode(routeDoc, "node5", session);
        node5.setPropertyValue(GraphNode.PROP_MERGE, "one");
        node5.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_rights1");
        node5.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node5 = session.saveDocument(node5);

        DocumentRoute route = instantiateAndRun(session);

        assertTrue(route.isDone());

        doc.refresh();
        assertEquals("title 1", doc.getTitle());
        assertEquals("descr 1", doc.getPropertyValue("dc:description"));
        // didn't go up to descr 2, which was canceled
        assertEquals("rights 1", doc.getPropertyValue("dc:rights"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testForkMergeWithLoopTransition() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1,
                transition("trans1", "node2", "true", "testchain_title1"),
                transition("trans2", "node2", "true", "testchain_descr1"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");
        node2.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_rights1");
        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        setTransitions(node2, transition("transloop", "node1", "false"));
        node2 = session.saveDocument(node2);

        DocumentRoute route = instantiateAndRun(session);

        assertTrue(route.isDone());
        doc.refresh();
        assertEquals("title 1", doc.getTitle());
        assertEquals("descr 1", doc.getPropertyValue("dc:description"));
        assertEquals("rights 1", doc.getPropertyValue("dc:rights"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testForkMergeWithTasksAndLoopTransitions() throws Exception {

        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        assertNotNull(user1);

        NuxeoPrincipal user2 = userManager.getPrincipal("myuser2");
        assertNotNull(user2);

        // Create nodes
        DocumentModel startNode = createNode(routeDoc, "startNode", session);
        startNode.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(startNode,
                transition("transToParallel1", "parallelNode1"),
                transition("transToParallel2", "parallelNode2"));
        startNode = session.saveDocument(startNode);

        DocumentModel parallelNode1 = createNode(routeDoc, "parallelNode1",
                session);
        parallelNode1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        String[] users1 = { user1.getName() };
        parallelNode1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users1);
        setTransitions(
                parallelNode1,
                transition("transLoop", "parallelNode1",
                        "NodeVariables[\"button\"] ==\"loop\""),
                transition("transToMerge", "mergeNode",
                        "NodeVariables[\"button\"] ==\"toMerge\""));
        parallelNode1 = session.saveDocument(parallelNode1);

        DocumentModel parallelNode2 = createNode(routeDoc, "parallelNode2",
                session);
        parallelNode2.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        String[] users2 = { user2.getName() };
        parallelNode2.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users2);
        setTransitions(
                parallelNode2,
                transition("transLoop", "parallelNode2",
                        "NodeVariables[\"button\"] ==\"loop\""),
                transition("transToMerge", "mergeNode",
                        "NodeVariables[\"button\"] ==\"toMerge\""));
        parallelNode2 = session.saveDocument(parallelNode2);

        DocumentModel mergeNode = createNode(routeDoc, "mergeNode", session);
        mergeNode.setPropertyValue(GraphNode.PROP_MERGE, "all");
        mergeNode.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        mergeNode.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users1);
        setTransitions(
                mergeNode,
                transition("transLoop", "startNode",
                        "NodeVariables[\"button\"] ==\"loop\""),
                transition("transEnd", "endNode",
                        "NodeVariables[\"button\"] ==\"end\""));
        mergeNode = session.saveDocument(mergeNode);

        DocumentModel endNode = createNode(routeDoc, "endNode", session);
        endNode.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        endNode = session.saveDocument(endNode);

        // Start route
        DocumentRoute route = instantiateAndRun(session);

        // Make user1 end his parallel task (1st time)
        List<Task> tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        Map<String, Object> data = new HashMap<String, Object>();
        CoreSession sessionUser1 = openSession(user1);
        routing.endTask(sessionUser1, tasks.get(0), data, "toMerge");
        closeSession(sessionUser1);

        // Make user2 end his parallel task (1st time)
        tasks = taskService.getTaskInstances(doc, user2, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        CoreSession sessionUser2 = openSession(user2);
        routing.endTask(sessionUser2, tasks.get(0), data, "toMerge");
        closeSession(sessionUser2);

        // Make user1 end the merge task choosing the "loop" transition
        tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        sessionUser1 = openSession(user1);
        routing.endTask(sessionUser1, tasks.get(0), data, "loop");
        closeSession(sessionUser1);

        // Make user1 end his parallel task (2nd time)
        tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        sessionUser1 = openSession(user1);
        routing.endTask(sessionUser1, tasks.get(0), data, "toMerge");
        closeSession(sessionUser1);

        // Make user2 end his parallel task (2nd time)
        tasks = taskService.getTaskInstances(doc, user2, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        sessionUser2 = openSession(user2);
        routing.endTask(sessionUser2, tasks.get(0), data, "toMerge");
        closeSession(sessionUser2);

        // Make user1 end the merge task choosing the "end" transition
        tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        sessionUser1 = openSession(user1);
        routing.endTask(sessionUser1, tasks.get(0), data, "end");
        closeSession(sessionUser1);

        // Check that route is done
        session.save();
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        assertTrue(route.isDone());
    }

    @SuppressWarnings("unchecked")
    @Test
    @Ignore
    // see NXP-10538
    public void testForkWithLoopFromParallelToFork() throws Exception {

        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        assertNotNull(user1);

        NuxeoPrincipal user2 = userManager.getPrincipal("myuser2");
        assertNotNull(user2);

        // Create nodes
        DocumentModel startNode = createNode(routeDoc, "startNode", session);
        startNode.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        startNode.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        String[] users1 = { user1.getName() };
        startNode.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users1);
        setTransitions(
                startNode,
                transition("transToParallel1", "parallelNode1",
                        "NodeVariables[\"button\"] ==\"validate\""),
                transition("transToParallel2", "parallelNode2",
                        "NodeVariables[\"button\"] ==\"validate\""));
        startNode = session.saveDocument(startNode);

        DocumentModel parallelNode1 = createNode(routeDoc, "parallelNode1",
                session);
        parallelNode1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        parallelNode1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users1);
        setTransitions(
                parallelNode1,
                transition("transLoop", "startNode",
                        "NodeVariables[\"button\"] ==\"loop\""),
                transition("transToMerge", "mergeNode",
                        "NodeVariables[\"button\"] ==\"toMerge\""));
        parallelNode1 = session.saveDocument(parallelNode1);

        DocumentModel parallelNode2 = createNode(routeDoc, "parallelNode2",
                session);
        parallelNode2.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        String[] users2 = { user2.getName() };
        parallelNode2.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users2);
        setTransitions(
                parallelNode2,
                transition("transToMerge", "mergeNode",
                        "NodeVariables[\"button\"] ==\"toMerge\""));
        parallelNode2 = session.saveDocument(parallelNode2);

        DocumentModel mergeNode = createNode(routeDoc, "mergeNode", session);
        mergeNode.setPropertyValue(GraphNode.PROP_MERGE, "all");
        startNode.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        mergeNode = session.saveDocument(mergeNode);

        // Start route
        DocumentRoute route = instantiateAndRun(session);

        // Make user1 validate the start task (1st time)
        List<Task> tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        Map<String, Object> data = new HashMap<String, Object>();
        CoreSession sessionUser1 = openSession(user1);
        routing.endTask(sessionUser1, tasks.get(0), data, "validate");
        closeSession(sessionUser1);

        // Make user1 loop to the start task
        tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        data = new HashMap<String, Object>();
        sessionUser1 = openSession(user1);
        routing.endTask(sessionUser1, tasks.get(0), data, "loop");
        closeSession(sessionUser1);

        // Make user1 validate the start task (2nd time)
        tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        data = new HashMap<String, Object>();
        sessionUser1 = openSession(user1);
        routing.endTask(sessionUser1, tasks.get(0), data, "validate");
        closeSession(sessionUser1);

        // Make user1 validate his parallel task
        tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        data = new HashMap<String, Object>();
        sessionUser1 = openSession(user1);
        routing.endTask(sessionUser1, tasks.get(0), data, "toMerge");
        closeSession(sessionUser1);

        // Make user2 end his parallel task
        tasks = taskService.getTaskInstances(doc, user2, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        CoreSession sessionUser2 = openSession(user2);
        routing.endTask(sessionUser2, tasks.get(0), data, "toMerge");
        closeSession(sessionUser2);

        // Check that route is done
        session.save();
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        assertTrue(route.isDone());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testRouteWithTasks() throws Exception {

        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        assertNotNull(user1);

        routeDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET,
                "FacetRoute1");
        routeDoc.addFacet("FacetRoute1");
        routeDoc = session.saveDocument(routeDoc);
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_VARIABLES_FACET, "FacetNode1");
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(
                node1,
                transition("trans1", "node2",
                        "NodeVariables[\"button\"] == \"trans1\"",
                        "testchain_title1"));

        // task properties

        node1.setPropertyValue(GraphNode.PROP_OUTPUT_CHAIN, "testchain_rights1");
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES_PERMISSION,
                "Write");
        node1.setPropertyValue(GraphNode.PROP_INPUT_CHAIN,
                "test_setGlobalvariable");
        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_TASK_DOC_TYPE, "MyTaskDoc");
        String[] users = { user1.getName() };
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users);
        setButtons(node1, button("btn1", "label-btn1", "filterrr"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");

        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node2 = session.saveDocument(node2);

        DocumentRoute route = instantiateAndRun(session);

        List<Task> tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        Map<String, Object> data = new HashMap<String, Object>();
        CoreSession sessionUser1 = openSession(user1);
        // task assignees have READ on the route instance
        assertNotNull(sessionUser1.getDocument(route.getDocument().getRef()));
        Task task1 = tasks.get(0);
        assertEquals("MyTaskDoc", task1.getDocument().getType());
        List<DocumentModel> docs = routing.getWorkflowInputDocuments(
                sessionUser1, task1);
        assertEquals(doc.getId(), docs.get(0).getId());
        routing.endTask(sessionUser1, tasks.get(0), data, "trans1");
        closeSession(sessionUser1);
        // end task and verify that route was done
        NuxeoPrincipal admin = new UserPrincipal("admin", null, false, true);
        session = openSession(admin);
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        assertTrue(route.isDone());
        assertEquals(
                "test",
                route.getDocument().getPropertyValue("fctroute1:globalVariable"));
        closeSession(session);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testEvaluateTaskAssigneesFromVariable() throws Exception {
        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        NuxeoPrincipal user2 = userManager.getPrincipal("myuser2");
        List<String> assignees = Arrays.asList(user1.getName(), user2.getName());

        routeDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET,
                "FacetRoute1");
        routeDoc.addFacet("FacetRoute1");
        routeDoc.setPropertyValue("fctroute1:myassignees",
                (Serializable) assignees);
        routeDoc = session.saveDocument(routeDoc);

        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_VARIABLES_FACET, "FacetNode1");
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_rights1");
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES_PERMISSION,
                "Write");
        setTransitions(node1,
                transition("trans1", "node2", "true", "testchain_title1"));
        // add a workflow variables with name "myassignees"
        node1.setPropertyValue("rnode:taskAssigneesExpr",
                "WorkflowVariables[\"myassignees\"]");
        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");
        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node2 = session.saveDocument(node2);

        DocumentRoute route = instantiateAndRun(session);

        List<Task> tasks = taskService.getTaskInstances(doc, user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());
        Task ts = tasks.get(0);
        assertEquals(2, ts.getActors().size());

        // check permissions set during task
        assertTrue(session.hasPermission(user1, doc.getRef(), "Write"));
        assertTrue(session.hasPermission(user2, doc.getRef(), "Write"));

        // end task

        Map<String, Object> data = new HashMap<String, Object>();
        CoreSession sessionUser2 = openSession(user2);
        routing.endTask(sessionUser2, tasks.get(0), data, "trans1");
        closeSession(sessionUser2);

        // verify that route was done
        NuxeoPrincipal admin = new UserPrincipal("admin", null, false, true);
        session = openSession(admin);
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        assertTrue(route.isDone());

        // permissions are reset
        assertFalse(session.hasPermission(user1, doc.getRef(), "Write"));
        assertFalse(session.hasPermission(user2, doc.getRef(), "Write"));

        closeSession(session);
    }

    /**
     * Check that when running as non-Administrator the assignees are set
     * correctly.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testComputedTaskAssignees() throws Exception {
        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        NuxeoPrincipal user2 = userManager.getPrincipal("myuser2");

        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_VARIABLES_FACET, "FacetNode1");
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_rights1");
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES_PERMISSION,
                "Write");
        setTransitions(node1,
                transition("trans1", "node2", "true", "testchain_title1"));
        // add a workflow node assignees expression
        node1.setPropertyValue("rnode:taskAssigneesExpr", "\"myuser1\"");
        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");
        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node2 = session.saveDocument(node2);

        session.save();

        // another session as user2
        CoreSession session2 = openSession(user2);

        DocumentRoute route = instantiateAndRun(session2);

        List<Task> tasks = taskService.getTaskInstances(doc, user1, session2);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());
        Task ts = tasks.get(0);
        assertEquals(1, ts.getActors().size());
        session2.save(); // flush invalidations
        closeSession(session2);

        // process task as user1
        CoreSession session1 = openSession(user1);
        try {
            routing.endTask(session1, tasks.get(0),
                    new HashMap<String, Object>(), "trans1");
        } finally {
            closeSession(session1);
        }

        // verify that route was done
        session.save(); // process invalidations
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        assertTrue(route.isDone());
        assertFalse(session.hasPermission(user1, doc.getRef(), "Write"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDynamicallyComputeDueDate() throws PropertyException,
            ClientException {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_VARIABLES_FACET, "FacetNode1");
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_rights1");
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES_PERMISSION,
                "Write");
        setTransitions(node1,
                transition("trans1", "node2", "true", "testchain_title1"));

        node1.setPropertyValue("rnode:taskAssigneesExpr", "\"Administrator\"");
        node1.setPropertyValue("rnode:taskDueDateExpr", "CurrentDate.days(1)");
        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");
        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node2 = session.saveDocument(node2);

        session.save();
        instantiateAndRun(session);

        List<Task> tasks = taskService.getTaskInstances(doc,
                (NuxeoPrincipal) session.getPrincipal(), session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());
        Task ts = tasks.get(0);
        Calendar currentDate = Calendar.getInstance();
        Calendar taskDueDate = Calendar.getInstance();
        taskDueDate.setTime(ts.getDueDate());
        int tomorrow = currentDate.get(Calendar.DAY_OF_YEAR) + 1;
        int due = taskDueDate.get(Calendar.DAY_OF_YEAR);
        if (due != 1) {
            assertEquals(tomorrow, due);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testWorkflowInitiatorAndTaskActor() throws Exception {
        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        NuxeoPrincipal user2 = userManager.getPrincipal("myuser2");

        routeDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET,
                "FacetRoute1");
        routeDoc.addFacet("FacetRoute1");
        routeDoc = session.saveDocument(routeDoc);

        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_VARIABLES_FACET, "FacetNode1");
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(
                node1,
                transition("trans1", "node2", "true",
                        "test_setGlobalVariableToWorkflowInitiator"));
        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES,
                new String[] { user2.getName() });
        setButtons(node1, button("btn1", "label-btn1", "filterrr"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node2 = session.saveDocument(node2);
        session.save();

        // start workflow as user1

        CoreSession sessionUser1 = openSession(user1);
        DocumentRoute route = instantiateAndRun(sessionUser1);
        DocumentRef routeDocRef = route.getDocument().getRef();
        closeSession(sessionUser1);

        // check user2 tasks

        List<Task> tasks = taskService.getTaskInstances(doc, user2, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        // continue task as user2

        CoreSession sessionUser2 = openSession(user2);
        // task assignees have READ on the route instance
        assertNotNull(sessionUser2.getDocument(routeDocRef));
        Task task = tasks.get(0);
        List<DocumentModel> docs = routing.getWorkflowInputDocuments(
                sessionUser2, task);
        assertEquals(doc.getId(), docs.get(0).getId());
        Map<String, Object> data = new HashMap<String, Object>();
        routing.endTask(sessionUser2, tasks.get(0), data, "trans1");
        closeSession(sessionUser2);

        // verify things
        NuxeoPrincipal admin = new UserPrincipal("admin", null, false, true);
        CoreSession sessionAdmin = openSession(admin);
        route = sessionAdmin.getDocument(routeDocRef).getAdapter(
                DocumentRoute.class);
        assertTrue(route.isDone());
        Serializable v = route.getDocument().getPropertyValue(
                "fctroute1:globalVariable");
        assertEquals("myuser1", v);
        closeSession(sessionAdmin);
    }

    @SuppressWarnings("unchecked")
    @Test
    @Ignore
    public void testRestartWorkflowOperation() throws Exception {
        assertEquals("file", doc.getTitle());
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1,
                transition("trans12", "node2", "true", "testchain_title1"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        setTransitions(node2, transition("trans23", "node3"));
        node2 = session.saveDocument(node2);

        DocumentModel node3 = createNode(routeDoc, "node3", session);
        node3.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node3 = session.saveDocument(node3);

        DocumentRoute route = instantiateAndRun(session);
        assertFalse(route.isDone());

        List<Task> tasks = taskService.getTaskInstances(doc,
                (NuxeoPrincipal) null, session);
        assertEquals(1, tasks.size());

        OperationContext ctx = new OperationContext(session);
        OperationChain chain = new OperationChain("testChain");
        chain.add(BulkRestartWorkflow.ID).set("workflowId", routeDoc.getTitle());
        automationService.run(ctx, chain);
        // process invalidations from automation context
        session.save();
        // query for all the workflows
        DocumentModelList workflows = session.query(String.format(
                "Select * from DocumentRoute where docri:participatingDocuments IN ('%s') and ecm:currentLifeCycleState = 'running'",
                doc.getId()));
        assertEquals(1, workflows.size());
        String restartedWorkflowId = workflows.get(0).getId();
        assertFalse(restartedWorkflowId.equals(route.getDocument().getId()));

        chain.add(BulkRestartWorkflow.ID).set("workflowId", routeDoc.getTitle()).set(
                "nodeId", "node2");
        automationService.run(ctx, chain);
        // process invalidations from automation context
        session.save();
        // query for all the workflows
        workflows = session.query(String.format(
                "Select * from DocumentRoute where docri:participatingDocuments IN ('%s') and ecm:currentLifeCycleState = 'running'",
                doc.getId()));
        assertEquals(1, workflows.size());
        assertFalse(restartedWorkflowId.equals(workflows.get(0).getId()));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testMergeOneWhenHavinOpenedTasks() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1, transition("trans12", "node2"),
                transition("trans13", "node3"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_title1");
        node2.setPropertyValue(GraphNode.PROP_HAS_TASK, "true");
        setTransitions(node2, transition("trans25", "node5"));
        node2 = session.saveDocument(node2);

        DocumentModel node3 = createNode(routeDoc, "node3", session);
        node3.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_descr1");
        setTransitions(node3, transition("trans34", "node4"));
        node3 = session.saveDocument(node3);

        DocumentModel node4 = createNode(routeDoc, "node4", session);
        node4.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_descr2");
        setTransitions(node4, transition("trans45", "node5"));
        node4.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        node4.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES,
                new String[] { user1.getName() });
        node4 = session.saveDocument(node4);

        DocumentModel node5 = createNode(routeDoc, "node5", session);
        node5.setPropertyValue(GraphNode.PROP_MERGE, "one");
        node5.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_rights1");
        node5.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node5 = session.saveDocument(node5);

        DocumentRoute route = instantiateAndRun(session);
        session.save(); // process invalidations
        // verify that there are 2 open tasks
        List<Task> tasks = taskService.getAllTaskInstances(
                route.getDocument().getId(), session);
        assertNotNull(tasks);
        assertEquals(2, tasks.size());

        tasks = taskService.getAllTaskInstances(route.getDocument().getId(),
                user1, session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());

        // process one of the tasks
        CoreSession session1 = openSession(user1);
        try {
            routing.endTask(session1, tasks.get(0),
                    new HashMap<String, Object>(), null);
        } finally {
            closeSession(session1);
        }

        // verify that route was done
        session.save(); // process invalidations
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        // verify that the merge one canceled the other tasks
        tasks = taskService.getAllTaskInstances(route.getDocument().getId(),
                session);
        assertNotNull(tasks);
        assertEquals(0, tasks.size());
        DocumentModelList cancelledTasks = session.query("Select * from TaskDoc where ecm:currentLifeCycleState = 'cancelled'");
        assertEquals(1, cancelledTasks.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testForceResumeOnMerge() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1, transition("trans12", "node2"),
                transition("trans13", "node3"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_title1");
        node2.setPropertyValue(GraphNode.PROP_HAS_TASK, "true");
        setTransitions(node2, transition("trans25", "node5"));
        node2 = session.saveDocument(node2);

        DocumentModel node3 = createNode(routeDoc, "node3", session);
        node3.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_descr1");
        setTransitions(node3, transition("trans34", "node4"));
        node3 = session.saveDocument(node3);

        DocumentModel node4 = createNode(routeDoc, "node4", session);
        node4.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_descr2");
        setTransitions(node4, transition("trans45", "node5"));
        node4 = session.saveDocument(node4);

        DocumentModel node5 = createNode(routeDoc, "node5", session);
        node5.setPropertyValue(GraphNode.PROP_MERGE, "all");
        node5.setPropertyValue(GraphNode.PROP_INPUT_CHAIN, "testchain_rights1");
        node5.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node5 = session.saveDocument(node5);

        DocumentRoute route = instantiateAndRun(session);
        // force resume on normal node, shouldn't change anything
        Map<String, Object> data = Collections.<String, Object> singletonMap(
                WORKFLOW_FORCE_RESUME, Boolean.TRUE);
        routing.resumeInstance(route.getDocument().getId(), "node2", data,
                null, session);
        session.save();
        assertEquals(
                "done",
                session.getDocument(route.getDocument().getRef()).getCurrentLifeCycleState());

        // force resume on merge on Waiting, but it shouldn't work
        // since the type of merge is all
        routeDoc = session.getDocument(routeDoc.getRef());
        route = instantiateAndRun(session);
        GraphRoute graph = (GraphRoute) route;
        GraphNode nodeMerge = graph.getNode("node5");
        assertTrue(State.WAITING.equals(nodeMerge.getState()));

        data = Collections.<String, Object> singletonMap(WORKFLOW_FORCE_RESUME,
                Boolean.TRUE);
        routing.resumeInstance(route.getDocument().getId(), "node5", data,
                null, session);
        session.save();

        // verify that the route is still running
        assertEquals(
                "running",
                session.getDocument(route.getDocument().getRef()).getCurrentLifeCycleState());

        // change merge type on the route instance and force resume again
        nodeMerge.getDocument().setPropertyValue(GraphNode.PROP_MERGE, "one");
        session.saveDocument(nodeMerge.getDocument());
        routing.resumeInstance(route.getDocument().getId(), "node5", data,
                null, session);
        session.save();

        assertEquals(
                "done",
                session.getDocument(route.getDocument().getRef()).getCurrentLifeCycleState());

    }

    @SuppressWarnings("unchecked")
    @Test
    public void testRouteWithExclusiveNode() throws Exception {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_EXECUTE_ONLY_FIRST_TRANSITION,
                Boolean.TRUE);
        setTransitions(node1,
                transition("trans12", "node2", "true", "testchain_title1"),
                transition("trans13", "node3", "true", "testchain_title2"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        setTransitions(node2, transition("trans24", "node4", "true"));
        node2 = session.saveDocument(node2);

        DocumentModel node3 = createNode(routeDoc, "node3", session);
        setTransitions(node3, transition("trans34", "node4", "true"));
        node3 = session.saveDocument(node3);

        DocumentModel node4 = createNode(routeDoc, "node4", session);
        node4.setPropertyValue(GraphNode.PROP_MERGE, "one");
        node4.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node4 = session.saveDocument(node4);

        DocumentRoute route = instantiateAndRun(session);
        assertTrue(route.isDone());

        session.save();

        // check that trans12 was executed and not trans13
        DocumentModel docR = session.getDocument(doc.getRef());
        assertEquals("title 1", docR.getTitle());
    }

    @SuppressWarnings("unchecked")
    protected void createWorkflowWithSubRoute(String subRouteModelId)
            throws ClientException, PropertyException {
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(node1, transition("trans12", "node2"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_SUB_ROUTE_MODEL_EXPR,
                subRouteModelId);
        setTransitions(node2, transition("trans23", "node3"));
        setSubRouteVariables(node2, keyvalue("stringfield", "foo"),
                keyvalue("globalVariable", "expr:bar@{4+3}baz"));
        node2 = session.saveDocument(node2);

        DocumentModel node3 = createNode(routeDoc, "node3", session);
        node3.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node3 = session.saveDocument(node3);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSubRouteNotSuspending() throws Exception {

        // create the sub-route

        DocumentModel subRouteDoc = createRoute("subroute", session);
        subRouteDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET,
                "FacetRoute1");
        subRouteDoc = session.saveDocument(subRouteDoc);

        DocumentModel subNode1 = createNode(subRouteDoc, "subnode1", session);
        subNode1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(
                subNode1,
                transition("trans12", "subnode2", "true",
                        "testchain_title_subroute"));
        subNode1 = session.saveDocument(subNode1);

        DocumentModel subNode2 = createNode(subRouteDoc, "subnode2", session);
        subNode2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        subNode2 = session.saveDocument(subNode2);

        validate(subRouteDoc, session);

        // create the base workflow

        createWorkflowWithSubRoute(subRouteDoc.getName());

        // start the main workflow
        DocumentRoute route = instantiateAndRun(session);

        // check that it's finished immediately
        assertTrue(route.isDone());
        // check that transition got the correct variables
        doc.refresh();
        assertEquals(route.getDocument().getId() + " node2 foo bar7baz",
                doc.getTitle());
    }

    @SuppressWarnings("unchecked")
    public void createRouteAndSuspendingSubRoute() throws Exception {

        // create the sub-route

        DocumentModel subRouteDoc = createRoute("subroute", session);
        subRouteDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET,
                "FacetRoute1");
        subRouteDoc = session.saveDocument(subRouteDoc);

        DocumentModel subNode1 = createNode(subRouteDoc, "subnode1", session);
        subNode1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(
                subNode1,
                transition("trans12", "subnode2", "true",
                        "testchain_title_subroute"));
        subNode1 = session.saveDocument(subNode1);

        DocumentModel subNode2 = createNode(subRouteDoc, "subnode2", session);
        subNode2.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        setTransitions(subNode2, transition("trans23", "subnode3"));
        subNode2 = session.saveDocument(subNode2);

        DocumentModel subNode3 = createNode(subRouteDoc, "subnode3", session);
        subNode3.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        subNode3 = session.saveDocument(subNode3);

        validate(subRouteDoc, session);

        // create the base workflow

        createWorkflowWithSubRoute(subRouteDoc.getName());
    }

    @Test
    public void testSubRouteSuspending() throws Exception {
        createRouteAndSuspendingSubRoute();

        // start the main workflow
        DocumentRoute route = instantiateAndRun(session);

        // check that it's suspended on node 2
        assertFalse(route.isDone());
        DocumentModel n2 = session.getChild(route.getDocument().getRef(),
                "node2");
        assertNotNull(n2);
        assertEquals(State.SUSPENDED.getLifeCycleState(),
                n2.getCurrentLifeCycleState());

        // check that transition got the correct variables
        doc.refresh();
        assertEquals(route.getDocument().getId() + " node2 foo bar7baz",
                doc.getTitle());

        // find the sub-route instance
        String subid = (String) n2.getPropertyValue(GraphNode.PROP_SUB_ROUTE_INSTANCE_ID);
        assertNotNull(subid);
        DocumentModel subrdoc = session.getDocument(new IdRef(subid));
        DocumentRoute subr = subrdoc.getAdapter(DocumentRoute.class);
        assertFalse(subr.isDone());

        // resume the sub-route node
        routing.resumeInstance(subid, "subnode2", null, null, session);
        // check sub-route done
        subrdoc.refresh();
        assertTrue(subr.isDone());
        // check main workflow also resumed and done
        route.getDocument().refresh();
        assertTrue(route.isDone());
    }

    @Test
    public void testSubRouteCancel() throws Exception {
        createRouteAndSuspendingSubRoute();

        // start the main workflow
        DocumentRoute route = instantiateAndRun(session);

        // check that it's suspended on node 2
        assertFalse(route.isDone());
        DocumentModel n2 = session.getChild(route.getDocument().getRef(),
                "node2");
        assertNotNull(n2);
        assertEquals(State.SUSPENDED.getLifeCycleState(),
                n2.getCurrentLifeCycleState());

        // cancel the main workflow
        route.cancel(session);
        route.getDocument().refresh();
        assertTrue(route.isCanceled());

        // find the sub-route instance
        String subid = (String) n2.getPropertyValue(GraphNode.PROP_SUB_ROUTE_INSTANCE_ID);
        assertNotNull(subid);
        DocumentModel subrdoc = session.getDocument(new IdRef(subid));
        DocumentRoute subr = subrdoc.getAdapter(DocumentRoute.class);

        // check hat it's canceled as well
        assertTrue(subr.isCanceled());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testCancelTasksWhenWorkflowDone() throws Exception {
        routeDoc = session.saveDocument(routeDoc);
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(
                node1,
                transition("trans1", "node12",
                        "NodeVariables[\"button\"] == \"trans1\"",
                        "testchain_title1"),
                transition("trans1", "node22",
                        "NodeVariables[\"button\"] == \"trans1\"",
                        "testchain_title1"),
                transition("trans2", "node2", "true", ""));

        node1.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        String[] users = { "Administrator" };
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users);
        node1 = session.saveDocument(node1);

        DocumentModel node12 = createNode(routeDoc, "node12", session);
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(
                node1,
                transition("trans12", "node2",
                        "NodeVariables[\"button\"] == \"trans12\"",
                        "testchain_title1"));

        node12.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node12 = session.saveDocument(node12);

        DocumentModel node22 = createNode(routeDoc, "node22", session);
        node22.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(
                node1,
                transition("trans22", "node2",
                        "NodeVariables[\"button\"] == \"trans22\"",
                        "testchain_title1"));

        node22.setPropertyValue(GraphNode.PROP_HAS_TASK, Boolean.TRUE);
        node22 = session.saveDocument(node22);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node2 = session.saveDocument(node2);
        DocumentRoute route = instantiateAndRun(session);
        session.save();

        List<Task> tasks = taskService.getAllTaskInstances(
                route.getDocument().getId(), session);
        assertNotNull(tasks);
        assertEquals(1, tasks.size());
        routing.endTask(session, tasks.get(0), new HashMap<String, Object>(),
                "trans1");
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        assertTrue(route.isDone());
        session.save();

        tasks = taskService.getAllTaskInstances(route.getDocument().getId(),
                session);
        assertEquals(0, tasks.size());
        DocumentModelList cancelledTasks = session.query("Select * from TaskDoc where ecm:currentLifeCycleState = 'cancelled'");
        assertEquals(2, cancelledTasks.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testRouteWithMultipleTasks() throws Exception {
        NuxeoPrincipal user1 = userManager.getPrincipal("myuser1");
        assertNotNull(user1);
        NuxeoPrincipal user2 = userManager.getPrincipal("myuser2");
        assertNotNull(user2);
        NuxeoPrincipal user3 = userManager.getPrincipal("myuser3");
        assertNotNull(user3);

        routeDoc.setPropertyValue(GraphRoute.PROP_VARIABLES_FACET,
                "FacetRoute1");
        routeDoc.addFacet("FacetRoute1");
        routeDoc = session.saveDocument(routeDoc);
        DocumentModel node1 = createNode(routeDoc, "node1", session);
        node1.setPropertyValue(GraphNode.PROP_VARIABLES_FACET, "FacetNode1");
        node1.setPropertyValue(GraphNode.PROP_START, Boolean.TRUE);
        setTransitions(
                node1,
                transition(
                        "trans1",
                        "node2",
                        "NodeVariables[\"tasks\"].getNumberEndedWithStatus(\"trans1\") ==1",
                        "testchain_title1"));

        // task properties
        node1.setPropertyValue(GraphNode.PROP_OUTPUT_CHAIN, "testchain_rights1");
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES_PERMISSION,
                "Write");
        node1.setPropertyValue(GraphNode.PROP_INPUT_CHAIN,
                "test_setGlobalvariable");
        node1.setPropertyValue(GraphNode.PROP_HAS_MULTIPLE_TASKS, Boolean.TRUE);
        node1.setPropertyValue(GraphNode.PROP_TASK_DOC_TYPE, "MyTaskDoc");

        // pass 3 assignees to create 3 tasks at this node
        String[] users = { user1.getName(), user2.getName(), user3.getName() };
        node1.setPropertyValue(GraphNode.PROP_TASK_ASSIGNEES, users);
        setButtons(node1, button("btn1", "label-btn1", "filterrr"));
        node1 = session.saveDocument(node1);

        DocumentModel node2 = createNode(routeDoc, "node2", session);
        node2.setPropertyValue(GraphNode.PROP_MERGE, "all");

        node2.setPropertyValue(GraphNode.PROP_STOP, Boolean.TRUE);
        node2 = session.saveDocument(node2);

        // run workflow
        DocumentRoute route = instantiateAndRun(session);
        GraphRoute graph = route.getDocument().getAdapter(GraphRoute.class);

        // verify that there are 3 tasks created from this node
        List<Task> tasks = taskService.getAllTaskInstances(
                route.getDocument().getId(), "node1", session);
        assertNotNull(tasks);
        assertEquals(3, tasks.size());
        assertEquals(3, graph.getNode("node1").getTasksInfo().size());

        // end first task as user 1
        Map<String, Object> data = new HashMap<String, Object>();
        CoreSession sessionUser1 = openSession(user1);
        assertNotNull(sessionUser1.getDocument(route.getDocument().getRef()));
        tasks = taskService.getTaskInstances(doc, user1, sessionUser1);
        assertEquals(1, tasks.size());
        Task task1 = tasks.get(0);
        assertEquals("MyTaskDoc", task1.getDocument().getType());
        List<DocumentModel> docs = routing.getWorkflowInputDocuments(
                sessionUser1, task1);
        assertEquals(doc.getId(), docs.get(0).getId());
        routing.endTask(sessionUser1, tasks.get(0), data, "faketrans1");
        closeSession(sessionUser1);

        // verify that route was not done, as there are still 2
        // open tasks
        NuxeoPrincipal admin = new UserPrincipal("admin", null, false, true);
        session = openSession(admin);
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        graph = route.getDocument().getAdapter(GraphRoute.class);
        assertFalse(route.isDone());
        assertEquals(1, graph.getNode("node1").getEndedTasksInfo().size());
        assertEquals(1, graph.getNode("node1").getProcessedTasksInfo().size());

        // end task2 as user 2
        data = new HashMap<String, Object>();
        data.put("comment", "testcomment");
        CoreSession sessionUser2 = openSession(user2);
        assertNotNull(sessionUser2.getDocument(route.getDocument().getRef()));

        tasks = taskService.getTaskInstances(doc, user2, sessionUser2);
        assertEquals(1, tasks.size());
        Task task2 = tasks.get(0);
        assertEquals("MyTaskDoc", task2.getDocument().getType());
        docs = routing.getWorkflowInputDocuments(sessionUser2, task2);
        assertEquals(doc.getId(), docs.get(0).getId());
        routing.endTask(sessionUser2, tasks.get(0), data, "trans1");
        closeSession(sessionUser2);

        // verify that route is not done yet, 2 tasks were done but there is
        // still one open
        session = openSession(admin);
        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        graph = route.getDocument().getAdapter(GraphRoute.class);
        assertFalse(route.isDone());
        assertEquals(2, graph.getNode("node1").getEndedTasksInfo().size());

        // cancel the last open task, resume the route and verify that route is
        // done now
        tasks = taskService.getTaskInstances(doc, user3, session);
        assertEquals(1, tasks.size());
        Task task3 = tasks.get(0);

        routing.cancelTask(session, route.getDocument().getId(), task3.getId());
        routing.resumeInstance(route.getDocument().getId(), "node1", null,
                null, session);

        route = session.getDocument(route.getDocument().getRef()).getAdapter(
                DocumentRoute.class);
        graph = route.getDocument().getAdapter(GraphRoute.class);

        assertTrue(route.isDone());
        assertEquals(3, graph.getNode("node1").getEndedTasksInfo().size());
        assertEquals(2, graph.getNode("node1").getProcessedTasksInfo().size());

        // also verify that the actor and the comment where updated on the node
        // when the tasks were completed or canceled
        GraphNode graphNode1 = graph.getNode("node1");
        List<GraphNode.TaskInfo> tasksInfo = graphNode1.getTasksInfo();
        assertEquals(tasksInfo.size(), 3);
        int task1Index = 0;
        int task2Index = 1;
        int task3Index = 2;
        for (TaskInfo taskInfo : tasksInfo) {
            if (taskInfo.getTaskDocId().equals(task1.getId())) {
                task1Index = tasksInfo.indexOf(taskInfo);
            }
            if (taskInfo.getTaskDocId().equals(task2.getId())) {
                task2Index = tasksInfo.indexOf(taskInfo);
            }
            if (taskInfo.getTaskDocId().equals(task3.getId())) {
                task3Index = tasksInfo.indexOf(taskInfo);
            }
        }

        assertEquals("myuser1", tasksInfo.get(task1Index).getActor());
        assertEquals("myuser2", tasksInfo.get(task2Index).getActor());
        // task3 was canceled as an admin
        assertEquals("admin", tasksInfo.get(task3Index).getActor());

        assertEquals("faketrans1", tasksInfo.get(task1Index).getStatus());
        assertEquals("trans1", tasksInfo.get(task2Index).getStatus());
        assertEquals("", tasksInfo.get(task3Index).getStatus());

        assertEquals("testcomment", tasksInfo.get(task2Index).getComment());
        closeSession(session);
    }
}
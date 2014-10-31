/*
 * ModeShape (http://www.modeshape.org)
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * See the AUTHORS.txt file in the distribution for a full listing of 
 * individual contributors.
 *
 * ModeShape is free software. Unless otherwise indicated, all code in ModeShape
 * is licensed to you under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 * 
 * ModeShape is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.modeshape.jcr;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.jcr.ImportUUIDBehavior;
import javax.jcr.Node;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.modeshape.common.FixFor;
import org.modeshape.common.statistic.Stopwatch;
import org.modeshape.common.util.FileUtil;
import org.modeshape.jcr.api.Problems;

/**
 * Test performance writing graph subtrees of various sizes with varying number of properties
 */
public class RepositoryBackupAndRestoreTest extends SingleUseAbstractTest {

    private File backupDirectory;
    private File backupDirectory2;

    @Override
    protected RepositoryConfiguration createRepositoryConfiguration( String repositoryName,
                                                                     Environment environment ) throws Exception {
        return RepositoryConfiguration.read("config/backup-repo-config.json").with(environment);
    }

    @Before
    @Override
    public void beforeEach() throws Exception {
        File backupArea = new File("target/backupArea");
        backupDirectory = new File(backupArea, "repoBackups");
        backupDirectory2 = new File(backupArea, "repoBackupsAfter");
        FileUtil.delete(backupArea);
        backupDirectory.mkdirs();
        backupDirectory2.mkdirs();
        new File(backupArea, "backRepo").mkdirs();
        new File(backupArea, "restoreRepo").mkdirs();
        super.beforeEach();
    }

    @Test
    @Ignore( "Comment out when generating and writing export files" )
    public void testExporting() throws Exception {
        print = true;
        String path = "/backupAndRestoreTestContent";
        populateRepositoryContent(session(), path);
        FileOutputStream stream = new FileOutputStream("src/test/resources/io/generated-3-system-view.xml");
        session().exportSystemView(path, stream, false, false);
        stream.close();
    }

    @Test
    public void shouldBackupRepositoryWithMultipleWorkspaces() throws Exception {
        loadContent();
        Problems problems = session().getWorkspace().getRepositoryManager().backupRepository(backupDirectory);
        assertNoProblems(problems);

        assertContentInWorkspace(repository(), "default");
        assertContentInWorkspace(repository(), "ws2");
        assertContentInWorkspace(repository(), "ws3");

        // Start up a new repository
        ((LocalEnvironment)environment).setShared(true);
        RepositoryConfiguration config = RepositoryConfiguration.read("config/restore-repo-config.json").with(environment);
        JcrRepository newRepository = new JcrRepository(config);
        try {
            newRepository.start();

            // And restore it from the contents ...
            JcrSession newSession = newRepository.login();
            try {
                Problems restoreProblems = newSession.getWorkspace().getRepositoryManager().restoreRepository(backupDirectory);
                assertNoProblems(restoreProblems);
            } finally {
                newSession.logout();
            }

            // Before we assert the content, create a backup of it (for comparison purposes when debugging) ...
            newSession = newRepository.login();
            try {
                Problems backupProblems = newSession.getWorkspace().getRepositoryManager().backupRepository(backupDirectory2);
                assertNoProblems(backupProblems);
            } finally {
                newSession.logout();
            }

            assertWorkspaces(newRepository, "default", "ws2", "ws3");

            assertContentInWorkspace(newRepository, null);
            assertContentInWorkspace(newRepository, "ws2");
            assertContentInWorkspace(newRepository, "ws3");
            queryContentInWorkspace(newRepository, null);
        } finally {
            newRepository.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @FixFor( "MODE-2309" )
    @Test
    public void shouldBackupAndRestoreRepositoryWithLineBreaksInPropertyValues() throws Exception {
        // Load the content and verify it's there ...
        importIntoWorkspace("default", "io/cars-system-view.xml");
        assertWorkspaces(repository(), "default");
        assertContentInWorkspace(repository(), "default");

        print = true;

        Node prius = session().getNode("/Cars/Hybrid/Toyota Prius");
        prius.setProperty("crlfproperty", "test\r\ntest\r\ntest");
        prius.setProperty("lfprop", "value\nvalue\nvalue");
        session().save();

        // Make the backup, and check that there are no problems ...
        Problems problems = session().getWorkspace().getRepositoryManager().backupRepository(backupDirectory);
        assertNoProblems(problems);

        // Make some changes that will not be in the backup ...
        session().getRootNode().addNode("node-not-in-backup");
        session().save();

        // Check the content again ...
        assertContentInWorkspace(repository(), "default", "/node-not-in-backup");

        // Restore the content from the backup into our current repository ...
        JcrSession newSession = repository().login();
        try {
            Problems restoreProblems = newSession.getWorkspace().getRepositoryManager().restoreRepository(backupDirectory);
            assertNoProblems(restoreProblems);
        } finally {
            newSession.logout();
        }

        assertWorkspaces(repository(), "default");

        // Check the content again ...
        assertContentInWorkspace(repository(), "default");
        assertContentNotInWorkspace(repository(), "default", "/node-not-in-backup");
        queryContentInWorkspace(repository(), null);
    }

    private void assertWorkspaces( JcrRepository newRepository,
                                   String... workspaceNames ) throws RepositoryException {
        Set<String> expectedNames = new HashSet<String>();
        for (String expectedName : workspaceNames) {
            expectedNames.add(expectedName);
        }

        Set<String> actualNames = new HashSet<String>();
        JcrSession session = newRepository.login();
        try {
            for (String actualName : session.getWorkspace().getAccessibleWorkspaceNames()) {
                actualNames.add(actualName);
            }
        } finally {
            session.logout();
        }

        assertThat(actualNames, is(expectedNames));
    }

    private void queryContentInWorkspace( JcrRepository newRepository,
                                          String workspaceName ) throws RepositoryException {
        JcrSession session = newRepository.login();
        try {
            String statement = "SELECT [car:model], [car:year], [car:msrp] FROM [car:Car] AS car";
            Query query = session.getWorkspace().getQueryManager().createQuery(statement, Query.JCR_SQL2);
            QueryResult results = query.execute();
            assertThat(results.getRows().getSize(), is(13L));
        } finally {
            session.logout();
        }
    }

    private void assertContentInWorkspace( JcrRepository newRepository,
                                           String workspaceName,
                                           String... paths ) throws RepositoryException {
        JcrSession session = workspaceName != null ? newRepository.login(workspaceName) : newRepository.login();

        try {
            session.getRootNode();
            session.getNode("/Cars");
            session.getNode("/Cars/Hybrid");
            session.getNode("/Cars/Hybrid/Toyota Prius");
            session.getNode("/Cars/Hybrid/Toyota Highlander");
            session.getNode("/Cars/Hybrid/Nissan Altima");
            session.getNode("/Cars/Sports/Aston Martin DB9");
            session.getNode("/Cars/Sports/Infiniti G37");
            session.getNode("/Cars/Luxury/Cadillac DTS");
            session.getNode("/Cars/Luxury/Bentley Continental");
            session.getNode("/Cars/Luxury/Lexus IS350");
            session.getNode("/Cars/Utility/Land Rover LR2");
            session.getNode("/Cars/Utility/Land Rover LR3");
            session.getNode("/Cars/Utility/Hummer H3");
            session.getNode("/Cars/Utility/Ford F-150");
            session.getNode("/Cars/Utility/Toyota Land Cruiser");
            for (String path : paths) {
                session.getNode(path);
            }
        } finally {
            session.logout();
        }
        queryContentInWorkspace(repository(), null);
    }

    private void assertContentNotInWorkspace( JcrRepository newRepository,
                                              String workspaceName,
                                              String... paths ) throws RepositoryException {
        JcrSession session = workspaceName != null ? newRepository.login(workspaceName) : newRepository.login();

        try {
            session.getRootNode();
            for (String path : paths) {
                try {
                    session.getNode(path);
                    fail("Should not have found '" + path + "'");
                } catch (PathNotFoundException e) {
                    // expected
                }
            }
        } finally {
            session.logout();
        }
    }

    @Test
    @FixFor( "MODE-2253" )
    public void shouldBackupAndRestoreWithExistingUserTransaction() throws Exception {
        loadContent();

        startTransaction();

        Problems problems = session().getWorkspace().getRepositoryManager().backupRepository(backupDirectory);
        assertNoProblems(problems);
        problems = session().getWorkspace().getRepositoryManager().restoreRepository(backupDirectory);
        assertNoProblems(problems);

        rollbackTransaction();

        assertContentInWorkspace(repository(), "default");
        assertContentInWorkspace(repository(), "ws2");
        assertContentInWorkspace(repository(), "ws3");
    }

    private void startTransaction() throws NotSupportedException, SystemException {
        TransactionManager txnMgr = session.repository.transactionManager();
        txnMgr.begin();
    }

    private void rollbackTransaction()
            throws SystemException, SecurityException, IllegalStateException, RollbackException, HeuristicMixedException,
                   HeuristicRollbackException {
        TransactionManager txnMgr = session.repository.transactionManager();
        txnMgr.rollback();
    }

    protected void assertNoProblems( Problems problems ) {
        if (problems.hasProblems()) {
            System.out.println(problems);
        }
        assertThat(problems.hasProblems(), is(false));
    }

    protected void loadContent() throws Exception {
        importIntoWorkspace("default", "io/cars-system-view.xml");
        importIntoWorkspace("ws2", "io/cars-system-view.xml");
        importIntoWorkspace("ws3", "io/cars-system-view.xml");
    }

    protected void importIntoWorkspace( String workspaceName,
                                        String resourcePath ) throws IOException, RepositoryException {
        Session session = null;
        try {
            session = repository().login(workspaceName);
        } catch (NoSuchWorkspaceException e) {
            // Create the workspace ...
            session().getWorkspace().createWorkspace(workspaceName);
            // Create a new session ...
            session = repository().login(workspaceName);
        }
        try {
            importContent(session.getRootNode(), resourcePath, ImportUUIDBehavior.IMPORT_UUID_COLLISION_THROW);
        } finally {
            session.logout();
        }
    }

    protected void populateRepositoryContent( Session session,
                                              String testName ) throws Exception {
        int depth = 6;
        int breadth = 3;
        int properties = 6;
        session.getRootNode().addNode(testName, "nt:unstructured");
        createSubgraph(session(), testName, depth, breadth, properties, false, new Stopwatch(), print ? System.out : null, null);
    }
}
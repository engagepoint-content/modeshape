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

import static org.modeshape.jcr.api.observation.Event.Sequencing.NODE_SEQUENCED;
import static org.modeshape.jcr.api.observation.Event.Sequencing.NODE_SEQUENCING_FAILURE;
import static org.modeshape.jcr.api.observation.Event.Sequencing.OUTPUT_PATH;
import static org.modeshape.jcr.api.observation.Event.Sequencing.SELECTED_PATH;
import static org.modeshape.jcr.api.observation.Event.Sequencing.SEQUENCED_NODE_ID;
import static org.modeshape.jcr.api.observation.Event.Sequencing.SEQUENCED_NODE_PATH;
import static org.modeshape.jcr.api.observation.Event.Sequencing.SEQUENCER_NAME;
import static org.modeshape.jcr.api.observation.Event.Sequencing.SEQUENCING_FAILURE_CAUSE;
import static org.modeshape.jcr.api.observation.Event.Sequencing.USER_ID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.jcr.AccessDeniedException;
import javax.jcr.RangeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventJournal;
import javax.jcr.observation.EventListener;
import javax.jcr.observation.EventListenerIterator;
import javax.jcr.observation.ObservationManager;
import org.modeshape.common.annotation.Immutable;
import org.modeshape.common.annotation.NotThreadSafe;
import org.modeshape.common.annotation.ThreadSafe;
import org.modeshape.common.logging.Logger;
import org.modeshape.common.util.CheckArg;
import org.modeshape.common.util.StringUtil;
import org.modeshape.jcr.api.observation.PropertyEvent;
import org.modeshape.jcr.api.value.DateTime;
import org.modeshape.jcr.cache.NodeKey;
import org.modeshape.jcr.cache.change.AbstractNodeChange;
import org.modeshape.jcr.cache.change.AbstractPropertyChange;
import org.modeshape.jcr.cache.change.AbstractSequencingChange;
import org.modeshape.jcr.cache.change.Change;
import org.modeshape.jcr.cache.change.ChangeSet;
import org.modeshape.jcr.cache.change.ChangeSetListener;
import org.modeshape.jcr.cache.change.NodeAdded;
import org.modeshape.jcr.cache.change.NodeMoved;
import org.modeshape.jcr.cache.change.NodeRemoved;
import org.modeshape.jcr.cache.change.NodeRenamed;
import org.modeshape.jcr.cache.change.NodeReordered;
import org.modeshape.jcr.cache.change.NodeSequenced;
import org.modeshape.jcr.cache.change.NodeSequencingFailure;
import org.modeshape.jcr.cache.change.Observable;
import org.modeshape.jcr.cache.change.PropertyAdded;
import org.modeshape.jcr.cache.change.PropertyChanged;
import org.modeshape.jcr.cache.change.PropertyRemoved;
import org.modeshape.jcr.value.Name;
import org.modeshape.jcr.value.Path;
import org.modeshape.jcr.value.PathFactory;

/**
 * The implementation of JCR {@link ObservationManager}.
 * 
 * @author Horia Chiorean
 */
@ThreadSafe
final class JcrObservationManager implements ObservationManager {

    /**
     * The key for storing the {@link JcrObservationManager#setUserData(String) observation user data} in the
     * {@link ExecutionContext}'s {@link ExecutionContext#getData() data}.
     */
    static final String OBSERVATION_USER_DATA_KEY = "org.modeshape.jcr.observation.userdata";

    /**
     * The keys which provide extra information in case of a move
     */
    static final String MOVE_FROM_KEY = "srcAbsPath";
    static final String MOVE_TO_KEY = "destAbsPath";

    /**
     * The keys which provide extra information in case of a reorder
     */
    static final String ORDER_DEST_KEY = "destChildRelPath";
    static final String ORDER_SRC_KEY = "srcChildRelPath";

    /**
     * The repository observable the JCR listeners will be registered with.
     */
    private final Observable repositoryObservable;

    /**
     * The JCR repository listener wrappers.
     */
    private final Set<JcrListenerAdapter> listeners;

    /**
     * The associated session.
     */
    protected final JcrSession session;

    /**
     * The name of the session's workspace; cached for performance reasons.
     */
    private final String workspaceName;
    private final String systemWorkspaceName;

    /**
     * @param session the owning session (never <code>null</code>)
     * @param repositoryObservable the repository observable used to register JCR listeners (never <code>null</code>)
     * @throws IllegalArgumentException if either parameter is <code>null</code>
     */
    JcrObservationManager( JcrSession session,
                           Observable repositoryObservable ) {
        CheckArg.isNotNull(session, "session");
        CheckArg.isNotNull(repositoryObservable, "repositoryObservable");

        this.session = session;
        this.workspaceName = this.session.getWorkspace().getName();
        this.systemWorkspaceName = this.session.repository().systemWorkspaceName();

        this.repositoryObservable = repositoryObservable;
        this.listeners = Collections.newSetFromMap(new ConcurrentHashMap<JcrListenerAdapter, Boolean>());
    }

    @Override
    public void addEventListener( EventListener listener,
                                  int eventTypes,
                                  String absPath,
                                  boolean isDeep,
                                  String[] uuid,
                                  String[] nodeTypeName,
                                  boolean noLocal ) throws RepositoryException {
        CheckArg.isNotNull(listener, "listener");
        checkSession(); // make sure session is still active

        // create wrapper and register
        JcrListenerAdapter adapter = new JcrListenerAdapter(listener, eventTypes, absPath, isDeep, uuid, nodeTypeName, noLocal);
        if (this.repositoryObservable.register(adapter)) {
            this.listeners.add(adapter);
        }
    }

    /**
     * @throws RepositoryException if session is not active
     */
    private void checkSession() throws RepositoryException {
        session.checkLive();
    }

    /**
     * @return the node type manager
     * @throws RepositoryException if there is a problem
     */
    JcrNodeTypeManager getNodeTypeManager() throws RepositoryException {
        return this.session.getWorkspace().getNodeTypeManager();
    }

    @Override
    public EventListenerIterator getRegisteredEventListeners() throws RepositoryException {
        checkSession(); // make sure session is still active
        return new JcrEventListenerIterator(Collections.unmodifiableSet(this.listeners));
    }

    final String stringFor( Path path ) {
        return session.stringFactory().create(path);
    }

    final String stringFor( Path.Segment segment ) {
        return this.session.stringFactory().create(segment);
    }

    final String stringFor( Name name ) {
        return this.session.stringFactory().create(name);
    }

    final PathFactory pathFactory() {
        return this.session.pathFactory();
    }

    String getSessionId() {
        return this.session.sessionId();
    }

    final String getWorkspaceName() {
        return workspaceName;
    }

    final String getSystemWorkspaceName() {
        return systemWorkspaceName;
    }

    final String nodeIdentifier( NodeKey key ) {
        return session.nodeIdentifier(key);
    }

    /**
     * Remove all of the listeners. This is typically called when the {@link JcrSession#logout() session logs out}.
     */
    void removeAllEventListeners() {
        for (JcrListenerAdapter adapter : this.listeners) {
            assert (adapter != null);
            this.repositoryObservable.unregister(adapter);
        }
        this.listeners.clear();
    }

    @Override
    public void removeEventListener( EventListener listener ) throws RepositoryException {
        checkSession(); // make sure session is still active
        CheckArg.isNotNull(listener, "listener");
        for (Iterator<JcrListenerAdapter> adapterIterator = listeners.iterator(); adapterIterator.hasNext(); ) {
            JcrListenerAdapter adapter = adapterIterator.next();
            assert (adapter != null);
            if (adapter.delegate.equals(listener)) {
                this.repositoryObservable.unregister(adapter);
                adapterIterator.remove();
                break;
            }
        }
    }

    @Override
    public void setUserData( String userData ) {
        // User data value may be null
        this.session.addContextData(OBSERVATION_USER_DATA_KEY, userData);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Since ModeShape does not support journaled observation, this method returns null.
     * </p>
     * 
     * @see javax.jcr.observation.ObservationManager#getEventJournal()
     */
    @Override
    public EventJournal getEventJournal() {
        return null;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Since ModeShape does not support journaled observation, this method returns null.
     * </p>
     * 
     * @see javax.jcr.observation.ObservationManager#getEventJournal(int, java.lang.String, boolean, java.lang.String[],
     *      java.lang.String[])
     */
    @Override
    public EventJournal getEventJournal( int eventTypes,
                                         String absPath,
                                         boolean isDeep,
                                         String[] uuid,
                                         String[] nodeTypeName ) {
        return null;
    }

    /**
     * An implementation of JCR {@link javax.jcr.RangeIterator} extended by the event and event listener iterators.
     * 
     * @param <E> the type being iterated over
     */
    protected class JcrRangeIterator<E> implements RangeIterator {

        /**
         * The elements being iterated over.
         */
        private final List<? extends E> elements;

        /**
         * The current position in the iterator.
         */
        private int position = 0;

        /**
         * @param elements the elements to iterator over
         * @throws IllegalArgumentException if <code>elements</code> is <code>null</code>
         */
        public JcrRangeIterator( Collection<? extends E> elements ) {
            CheckArg.isNotNull(elements, "elements");
            this.elements = new ArrayList<E>(elements);
        }

        @Override
        public long getPosition() {
            return this.position;
        }

        @Override
        public long getSize() {
            return this.elements.size();
        }

        @Override
        public boolean hasNext() {
            return (getPosition() < getSize());
        }

        @Override
        public Object next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }

            Object element = this.elements.get(this.position);
            ++this.position;

            return element;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void skip( long skipNum ) {
            this.position += skipNum;

            if (!hasNext()) {
                throw new NoSuchElementException();
            }
        }
    }

    /**
     * An implementation of the JCR {@link EventListenerIterator}.
     */
    protected class JcrEventListenerIterator extends JcrRangeIterator<JcrListenerAdapter> implements EventListenerIterator {

        /**
         * @param listeners the listeners being iterated over
         * @throws IllegalArgumentException if <code>listeners</code> is <code>null</code>
         */
        public JcrEventListenerIterator( Collection<JcrListenerAdapter> listeners ) {
            super(listeners);
        }

        @Override
        public EventListener nextEventListener() {
            return ((JcrListenerAdapter) next()).delegate;
        }
    }

    /**
     * An implementation of JCR {@link javax.jcr.observation.EventIterator}.
     */
    protected class JcrEventIterator extends JcrRangeIterator<Event> implements EventIterator {

        /**
         * @param events the events being iterated over
         * @throws IllegalArgumentException if <code>events</code> is <code>null</code>
         */
        public JcrEventIterator( Collection<Event> events ) {
            super(events);
        }

        @Override
        public Event nextEvent() {
            return (Event)next();
        }
    }

    /**
     * The information related to and shared by a set of events that represent a single logical operation.
     */
    @Immutable
    protected class JcrEventBundle {

        /**
         * The date and time of the event bundle.
         */
        private final DateTime date;

        /**
         * The user ID.
         */
        private final String userId;

        /**
         * The optional user data string (may be null)
         */
        private final String userData;

        public JcrEventBundle( DateTime dateTime,
                               String userId,
                               String userData ) {
            this.userId = userId;
            this.userData = userData;
            this.date = dateTime;
        }

        public String getUserID() {
            return this.userId;
        }

        /**
         * @return date
         */
        public DateTime getDate() {
            return date;
        }

        /**
         * @return userData
         */
        public String getUserData() {
            return userData;
        }
    }

    /**
     * An implementation of JCR {@link org.modeshape.jcr.api.observation.Event}.
     */
    @Immutable
    protected class JcrEvent implements org.modeshape.jcr.api.observation.Event {

        private final String id;

        /**
         * The node path.
         */
        private final String path;

        /**
         * The event type.
         */
        private final int type;

        /**
         * The immutable bundle information, which may be shared amongst multiple events.
         */
        private final JcrEventBundle bundle;

        /**
         * A map of extra information for regarding the event
         */
        private Map<String, ?> info;

        /**
         * @param bundle the event bundle information
         * @param type the event type
         * @param path the node path
         * @param id the node identifier
         */
        JcrEvent( JcrEventBundle bundle,
                  int type,
                  String path,
                  String id ) {
            this.type = type;
            this.path = path;
            this.bundle = bundle;
            this.id = id;
        }

        JcrEvent( JcrEventBundle bundle,
                  int type,
                  String path,
                  String id,
                  Map<String, ?> info ) {
            this(bundle, type, path, id);
            this.info = info;
        }

        @Override
        public String getPath() {
            return this.path;
        }

        @Override
        public int getType() {
            return this.type;
        }

        @Override
        public String getUserID() {
            return bundle.getUserID();
        }

        @Override
        public long getDate() {
            return bundle.getDate().getMilliseconds();
        }

        @Override
        public String getIdentifier() {
            return id;
        }

        @Override
        public String getUserData() {
            return bundle.getUserData();
        }

        @Override
        public Map<String, ?> getInfo() {
            return info != null ? Collections.unmodifiableMap(info) : Collections.<String, String>emptyMap();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            switch (this.type) {
                case Event.NODE_ADDED:
                    sb.append("Node added");
                    break;
                case Event.NODE_REMOVED:
                    sb.append("Node removed");
                    break;
                case Event.PROPERTY_ADDED:
                    sb.append("Property added");
                    break;
                case Event.PROPERTY_CHANGED:
                    sb.append("Property changed");
                    break;
                case Event.PROPERTY_REMOVED:
                    sb.append("Property removed");
                    break;
                case Event.NODE_MOVED:
                    if (info.containsKey(MOVE_FROM_KEY) || info.containsKey(MOVE_TO_KEY)) {
                        sb.append("Node moved");
                        sb.append(" from ").append(info.get(MOVE_FROM_KEY)).append(" to ").append(info.get(MOVE_TO_KEY));
                    } else {
                        sb.append("Node reordered");
                        String destination = info.get(ORDER_DEST_KEY).toString();
                        if (destination == null) {
                            destination = " at the end of the children list";
                        }
                        Object source = info.get(ORDER_SRC_KEY);
                        if (source != null) {
                            sb.append(" from ").append(source);
                        }
                        sb.append(" to ").append(destination);
                    }
                    sb.append(" by ").append(getUserID());
                    return sb.toString();
                case NODE_SEQUENCED:
                    sb.append("Node sequenced");
                    sb.append(" sequenced node:")
                      .append(info.get(SEQUENCED_NODE_ID))
                      .append(" at path:")
                      .append(info.get(SEQUENCED_NODE_PATH));
                    sb.append(" ,output node:").append(getIdentifier()).append(" at path:").append(getPath());
                    return sb.toString();
                case NODE_SEQUENCING_FAILURE: {
                    sb.append("Node sequencing failure");
                    sb.append(" sequenced node:")
                      .append(info.get(SEQUENCED_NODE_ID))
                      .append(" at path:")
                      .append(info.get(SEQUENCED_NODE_PATH));
                    sb.append(" ,cause: ").append(getInfo().get(SEQUENCING_FAILURE_CAUSE));
                    return sb.toString();
                }
            }
            sb.append(" at ").append(path).append(" by ").append(getUserID());
            return sb.toString();
        }
    }

    class JcrPropertyEvent extends JcrEvent implements PropertyEvent {
        private final Object currentValue;
        private final Object oldValue;

        JcrPropertyEvent( JcrEventBundle bundle,
                          int type,
                          String path,
                          String id,
                          Object currentValue,
                          Object oldValue ) {
            super(bundle, type, path, id);
            this.currentValue = currentValue;
            this.oldValue = oldValue;
        }

        JcrPropertyEvent( JcrEventBundle bundle,
                          int type,
                          String path,
                          String id,
                          Object currentValue ) {
            this(bundle, type, path, id, currentValue, null);
        }

        @Override
        public Object getCurrentValue() {
            return firstValueFrom(currentValue);
        }

        @Override
        public boolean isMultiValue() {
            return currentValue instanceof Object[];
        }

        @Override
        public List<?> getCurrentValues() {
            return listValueFrom(currentValue);
        }

        @Override
        public boolean wasMultiValue() {
            return oldValue instanceof Object[];
        }

        @Override
        public Object getPreviousValue() {
            return firstValueFrom(oldValue);
        }

        @Override
        public List<?> getPreviousValues() {
            return listValueFrom(oldValue);
        }

        private List<?> listValueFrom( Object value ) {
            if (value == null) {
                return null;
            }
            if (value instanceof Object[]) {
                return ((Object[])value).length > 0 ? Arrays.asList((Object[])value) : Collections.emptyList();
            }
            return Arrays.asList(value);
        }

        private Object firstValueFrom( Object value ) {
            if (value == null) {
                return null;
            }
            if (value instanceof Object[]) {
                return ((Object[])value).length > 0 ? ((Object[])value)[0] : null;
            }
            return value;
        }
    }

    /**
     * The <code>JcrListener</code> class wraps JCR {@link EventListener} and is responsible for converting
     * {@link org.modeshape.jcr.cache.change.Change events} into JCR {@link Event events}.
     */
    @NotThreadSafe
    class JcrListenerAdapter implements ChangeSetListener {

        private final Logger logger = Logger.getLogger(getClass());

        /**
         * The node path whose events should be handled (or <code>null</code>) if all node paths should be handled.
         */
        private final String absPath;

        /**
         * The JCR event listener.
         */
        private final EventListener delegate;

        /**
         * The event types this listener is interested in handling.
         */
        private final int eventTypes;

        /**
         * A flag indicating if events of child nodes of the <code>absPath</code> should be processed.
         */
        private final boolean isDeep;

        /**
         * The node type names or <code>null</code>. If a node with one of these types is the source node of an event than this
         * listener wants to process that event. If <code>null</code> or empty than this listener wants to handle nodes of any
         * type.
         */
        private final String[] nodeTypeNames;

        /**
         * A flag indicating if events generated by the session that registered this listener should be ignored.
         */
        private final boolean noLocal;

        /**
         * The node UUIDs or <code>null</code>. If a node with one of these UUIDs is the source node of an event than this
         * listener wants to handle this event. If <code>null</code> or empty than this listener wants to handle nodes with any
         * UUID.
         */
        private final Set<String> uuids;

        /**
         * @param delegate the JCR listener
         * @param eventTypes a combination of one or more JCR event types
         * @param absPath the absolute path of a node or <code>null</code> if all node paths
         * @param isDeep indicates if paths below <code>absPath</code> should be considered
         * @param uuids UUIDs or <code>null</code>
         * @param nodeTypeNames node type names or <code>null</code>
         * @param noLocal indicates if events from this listener's session should be ignored
         */
        JcrListenerAdapter( EventListener delegate,
                            int eventTypes,
                            String absPath,
                            boolean isDeep,
                            String[] uuids,
                            String[] nodeTypeNames,
                            boolean noLocal ) {
            assert (delegate != null);

            this.delegate = delegate;
            this.eventTypes = eventTypes;
            this.absPath = absPath;
            this.isDeep = isDeep;
            if (uuids == null) {
                this.uuids = null;
            } else if (uuids.length == 0) {
                this.uuids = Collections.emptySet();
            } else {
                this.uuids = new HashSet<String>(Arrays.asList(uuids));
            }
            this.nodeTypeNames = nodeTypeNames;
            this.noLocal = noLocal;
        }

        @SuppressWarnings( "synthetic-access" )
        @Override
        public void notify( ChangeSet changeSet ) {
            if (shouldReject(changeSet)) {
                return;
            }

            Collection<Event> events = new ArrayList<Event>();

            String userData = changeSet.getUserData().get(OBSERVATION_USER_DATA_KEY);
            JcrEventBundle bundle = new JcrEventBundle(changeSet.getTimestamp(), changeSet.getUserId(), userData);

            for (Change change : changeSet) {
                processChange(events, bundle, change);
            }

            // notify delegate
            if (!events.isEmpty()) {
                this.delegate.onEvent(new JcrEventIterator(events));
            }
        }

        private boolean shouldReject( ChangeSet changeSet ) {
            return !acceptBasedOnOriginatingSession(changeSet) || !acceptBasedOnOriginatingWorkspace(changeSet);
        }

        private void processChange( Collection<Event> events,
                                    JcrEventBundle bundle,
                                    Change change ) {
            if (!(change instanceof AbstractNodeChange)) {
                return;
            }
            AbstractNodeChange nodeChange = (AbstractNodeChange)change;
            if (logger.isDebugEnabled()) {
                logger.debug("Received change: " + nodeChange);
            }

            if (shouldReject(nodeChange)) {
                return;
            }

            // process event making sure we have the right event type
            Path newPath = nodeChange.getPath();
            String nodeId = nodeIdentifier(nodeChange.getKey());

            // node moved
            if (nodeChange instanceof NodeMoved) {
                NodeMoved nodeMovedChange = (NodeMoved)nodeChange;
                Path oldPath = nodeMovedChange.getOldPath();
                fireNodeMoved(events, bundle, newPath, nodeId, oldPath);

            } else if (nodeChange instanceof NodeRenamed) {
                NodeRenamed nodeRenamedChange = (NodeRenamed)nodeChange;
                Path oldPath = pathFactory().create(newPath.subpath(0, newPath.size() - 1), nodeRenamedChange.getOldSegment());
                fireNodeMoved(events, bundle, newPath, nodeId, oldPath);

            } else if (nodeChange instanceof NodeReordered) {
                NodeReordered nodeReordered = (NodeReordered)nodeChange;
                Path oldPath = nodeReordered.getOldPath();

                if (eventListenedFor(Event.NODE_MOVED)) {
                    Map<String, String> info = new HashMap<String, String>();
                    // check if the reordering wasn't at the end by any chance
                    if (nodeReordered.getReorderedBeforePath() != null) {
                        info.put(ORDER_DEST_KEY, stringFor(nodeReordered.getReorderedBeforePath().getLastSegment()));
                    } else {
                        info.put(ORDER_DEST_KEY, null);
                    }
                    if (oldPath != null) {
                        info.put(ORDER_SRC_KEY, stringFor(oldPath.getLastSegment()));
                    }
                    events.add(new JcrEvent(bundle, Event.NODE_MOVED, stringFor(newPath), nodeId,
                                            Collections.unmodifiableMap(info)));
                }

                fireExtraEventsForMove(events, bundle, newPath, nodeId, oldPath);
            } else if (nodeChange instanceof NodeAdded && eventListenedFor(Event.NODE_ADDED)) {
                // create event for added node
                events.add(new JcrEvent(bundle, Event.NODE_ADDED, stringFor(newPath), nodeId));
            } else if (nodeChange instanceof NodeRemoved && eventListenedFor(Event.NODE_REMOVED)) {
                // create event for removed node
                events.add(new JcrEvent(bundle, Event.NODE_REMOVED, stringFor(newPath), nodeId));
            } else if (nodeChange instanceof PropertyChanged && eventListenedFor(Event.PROPERTY_CHANGED)) {
                // create event for changed property
                PropertyChanged propertyChanged = (PropertyChanged)nodeChange;
                Name propertyName = propertyChanged.getNewProperty().getName();
                Path propertyPath = pathFactory().create(newPath, stringFor(propertyName));

                boolean isMultiValue = propertyChanged.getNewProperty().isMultiple();
                Object currentValue = isMultiValue ? propertyChanged.getNewProperty().getValuesAsArray() : propertyChanged.getNewProperty()
                                                                                                                          .getFirstValue();

                boolean wasMultiValue = propertyChanged.getOldProperty().isMultiple();
                Object oldValue = wasMultiValue ? propertyChanged.getOldProperty().getValuesAsArray() : propertyChanged.getOldProperty()
                                                                                                                       .getFirstValue();

                events.add(new JcrPropertyEvent(bundle, Event.PROPERTY_CHANGED, stringFor(propertyPath), nodeId, currentValue,
                                                oldValue));
            } else if (nodeChange instanceof PropertyAdded && eventListenedFor(Event.PROPERTY_ADDED)) {
                PropertyAdded propertyAdded = (PropertyAdded)nodeChange;
                Name propertyName = propertyAdded.getProperty().getName();
                Path propertyPath = pathFactory().create(newPath, stringFor(propertyName));

                boolean isMultiValue = propertyAdded.getProperty().isMultiple();
                Object currentValue = isMultiValue ? propertyAdded.getProperty().getValuesAsArray() : propertyAdded.getProperty()
                                                                                                                   .getFirstValue();

                events.add(new JcrPropertyEvent(bundle, Event.PROPERTY_ADDED, stringFor(propertyPath), nodeId, currentValue));

            } else if (nodeChange instanceof PropertyRemoved && eventListenedFor(Event.PROPERTY_REMOVED)) {
                // create event for removed property
                PropertyRemoved propertyRemoved = (PropertyRemoved)nodeChange;
                Name propertyName = propertyRemoved.getProperty().getName();
                Path propertyPath = pathFactory().create(newPath, propertyName);

                boolean isMultiValue = propertyRemoved.getProperty().isMultiple();
                Object currentValue = isMultiValue ? propertyRemoved.getProperty().getValuesAsArray() : propertyRemoved.getProperty()
                                                                                                                       .getFirstValue();

                events.add(new JcrPropertyEvent(bundle, Event.PROPERTY_REMOVED, stringFor(propertyPath), nodeId, currentValue));
            } else if (nodeChange instanceof NodeSequenced && eventListenedFor(NODE_SEQUENCED)) {
                // create event for the sequenced node
                NodeSequenced sequencedChange = (NodeSequenced)nodeChange;

                Map<String, Object> infoMap = createEventInfoMapForSequencerChange(sequencedChange);
                events.add(new JcrEvent(bundle, NODE_SEQUENCED, stringFor(sequencedChange.getOutputNodePath()),
                                        nodeIdentifier(sequencedChange.getOutputNodeKey()), infoMap));
            } else if (nodeChange instanceof NodeSequencingFailure && eventListenedFor(NODE_SEQUENCING_FAILURE)) {
                // create event for the sequencing failure
                NodeSequencingFailure sequencingFailure = (NodeSequencingFailure)nodeChange;

                Map<String, Object> infoMap = createEventInfoMapForSequencerChange(sequencingFailure);
                infoMap.put(SEQUENCING_FAILURE_CAUSE, sequencingFailure.getCause());
                events.add(new JcrEvent(bundle, NODE_SEQUENCING_FAILURE, stringFor(sequencingFailure.getPath()), nodeId, infoMap));
            }
        }

        private Map<String, Object> createEventInfoMapForSequencerChange( AbstractSequencingChange sequencingChange ) {
            Map<String, Object> infoMap = new HashMap<String, Object>();

            infoMap.put(SEQUENCED_NODE_PATH, stringFor(sequencingChange.getPath()));
            infoMap.put(SEQUENCED_NODE_ID, nodeIdentifier(sequencingChange.getKey()));
            infoMap.put(OUTPUT_PATH, sequencingChange.getOutputPath());
            infoMap.put(SELECTED_PATH, sequencingChange.getSelectedPath());
            infoMap.put(SEQUENCER_NAME, sequencingChange.getSequencerName());
            infoMap.put(USER_ID, sequencingChange.getUserId());

            return infoMap;
        }

        private void fireNodeMoved( Collection<Event> events,
                                    JcrEventBundle bundle,
                                    Path newPath,
                                    String nodeId,
                                    Path oldPath ) {
            if (eventListenedFor(Event.NODE_MOVED)) {
                Map<String, String> info = new HashMap<String, String>();
                info.put(MOVE_FROM_KEY, stringFor(oldPath));
                info.put(MOVE_TO_KEY, stringFor(newPath));

                events.add(new JcrEvent(bundle, Event.NODE_MOVED, stringFor(newPath), nodeId, Collections.unmodifiableMap(info)));
            }
            fireExtraEventsForMove(events, bundle, newPath, nodeId, oldPath);
        }

        private void fireExtraEventsForMove( Collection<Event> events,
                                             JcrEventBundle bundle,
                                             Path newPath,
                                             String nodeId,
                                             Path oldPath ) {
            // JCR 1.0 expects these methods <i>in addition to</i> the NODE_MOVED event
            if (eventListenedFor(Event.NODE_ADDED)) {
                events.add(new JcrEvent(bundle, Event.NODE_ADDED, stringFor(newPath), nodeId));
            }
            if (eventListenedFor(Event.NODE_REMOVED)) {
                events.add(new JcrEvent(bundle, Event.NODE_REMOVED, stringFor(oldPath), nodeId));
            }
        }

        private boolean shouldReject( AbstractNodeChange nodeChange ) {
            return !acceptBasedOnUuid(nodeChange)  || !acceptBasedOnPath(nodeChange) || !acceptBasedOnPermission(nodeChange)
                   || !acceptIfLockChange(nodeChange)|| !acceptBasedOnNodeTypeName(nodeChange);
        }

        /**
         * In case of changes involving locks from the system workspace, the TCK expects that the only property changes be for
         * lock owner and lock isDeep, which will be fired from the locked node. Therefore, we should exclude property
         * notifications from the lock node from the system workspace.
         * 
         * @param nodeChange the internal event
         * @return true if the change should be accepted/propagated
         */
        private boolean acceptIfLockChange( AbstractNodeChange nodeChange ) {
            if (!(nodeChange instanceof PropertyAdded || nodeChange instanceof PropertyRemoved || nodeChange instanceof PropertyChanged)) {
                return true;
            }
            Path path = nodeChange.getPath();
            if (path.size() < 2) {
                return true;
            }
            Name firstSegmentName = path.subpath(0, 1).getLastSegment().getName();
            boolean isSystemLockChange = JcrLexicon.SYSTEM.equals(firstSegmentName)
                                         && ModeShapeLexicon.LOCKS.equals(path.getParent().getLastSegment().getName());
            return !isSystemLockChange;
        }

        private boolean eventListenedFor( int eventType ) {
            return (this.eventTypes & eventType) == eventType;
        }

        /**
         * @param nodeChange the change being processed
         * @return <code>true</code> if the {@link JcrSession#checkPermission(org.modeshape.jcr.value.Path, String...)}
         * returns true for a {@link ModeShapePermissions#READ} permission on the node from the change
         */
        @SuppressWarnings( "synthetic-access" )
        private boolean acceptBasedOnPermission( AbstractNodeChange nodeChange ) {
            try {
                session.checkPermission(parentNodePathOfChange(nodeChange), ModeShapePermissions.READ);
                return true;
            } catch (AccessDeniedException e) {
                return false;
            }
        }

        private boolean acceptBasedOnOriginatingWorkspace( ChangeSet changeSet ) {
            boolean sameWorkspace = getWorkspaceName().equalsIgnoreCase(changeSet.getWorkspaceName());
            boolean isSystemWorkspace = getSystemWorkspaceName().equalsIgnoreCase(changeSet.getWorkspaceName());
            return sameWorkspace || isSystemWorkspace;
        }

        /**
         * @param changeSet the changes being processed
         * @return <code>true</code> if event occurred in a different session or if events from same session should be processed
         */
        private boolean acceptBasedOnOriginatingSession( ChangeSet changeSet ) {
            return !this.noLocal || !getSessionId().equals(changeSet.getSessionId());
        }

        /**
         * @param change the change being processed
         * @return <code>true</code> if all node types should be processed or if changed node type name matches a specified type
         */
        @SuppressWarnings( "synthetic-access" )
        private boolean acceptBasedOnNodeTypeName( AbstractNodeChange change ) {
            // JSR 283#12.5.3.4.3
            if (nodeTypeNames != null && nodeTypeNames.length == 0) {
                return false;
            }
            String[] mixinStrings = null;
            if (shouldCheckNodeType()) {
                String primaryTypeName = null;
                try {
                    AbstractJcrNode parentNode = null;
                    if (change instanceof AbstractPropertyChange) {
                        // we can optimize this case, because we can get the parent node directly via key
                        parentNode = session.node(change.getKey(), null);
                    } else {
                        Path parentPath = parentNodePathOfChange(change);
                        parentNode = session.node(parentPath);
                    }

                    Set<Name> parentMixinNames = parentNode.getMixinTypeNames();
                    mixinStrings = new String[parentMixinNames.size()];
                    int i = 0;
                    for (Name mixinName : parentMixinNames) {
                        mixinStrings[i++] = stringFor(mixinName);
                    }
                    primaryTypeName = stringFor(parentNode.getPrimaryTypeName());
                    return getNodeTypeManager().isDerivedFrom(this.nodeTypeNames,
                                                              primaryTypeName,
                                                              mixinStrings);
                } catch (RepositoryException e) {
                    logger.error(e, JcrI18n.cannotPerformNodeTypeCheck, primaryTypeName, Arrays.toString(mixinStrings), this.nodeTypeNames);
                    return false;
                }
            }

            return true;
        }

        /**
         * @param change the change being processed
         * @return <code>true</code> if there is no absolute path or if change path matches or optionally is a deep match
         */
        @SuppressWarnings( "synthetic-access" )
        private boolean acceptBasedOnPath( AbstractNodeChange change ) {
            if (!StringUtil.isBlank(absPath)) {
                Path matchPath = session.pathFactory().create(this.absPath);
                Path parentPath = parentNodePathOfChange(change);

                return this.isDeep ? matchPath.isAtOrAbove(parentPath) : matchPath.equals(parentPath);
            }
            return true;
        }

        /**
         * @param change the change being processed
         * @return <code>true</code> if there are no UUIDs to match or change UUID matches
         */
        private boolean acceptBasedOnUuid( AbstractNodeChange change ) {
            // JSR_283#12.5.3.4.2
            return this.uuids == null || (!this.uuids.isEmpty() && this.uuids.contains(nodeIdentifier(change.getKey())));
        }

        private Path parentNodePathOfChange( AbstractNodeChange change ) {
            Path changePath = change.getPath();
            if (change instanceof AbstractPropertyChange) {
                return changePath;
            }
            return changePath.isRoot() ? changePath : changePath.getParent();
        }

        @Override
        public boolean equals( Object obj ) {
            return (obj != null) && (obj instanceof JcrListenerAdapter) && (this.delegate == ((JcrListenerAdapter)obj).delegate);

        }

        @Override
        public int hashCode() {
            return this.delegate.hashCode();
        }

        /**
         * @return <code>true</code> if the node type of the event locations need to be checked
         */
        private boolean shouldCheckNodeType() {
            return ((this.nodeTypeNames != null) && (this.nodeTypeNames.length > 0));
        }
    }
}

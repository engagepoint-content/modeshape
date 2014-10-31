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
package org.modeshape.jcr.cache.document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.infinispan.schematic.document.Document;
import org.modeshape.common.annotation.Immutable;
import org.modeshape.common.collection.EmptyIterator;
import org.modeshape.common.collection.LinkedListMultimap;
import org.modeshape.common.collection.ListMultimap;
import org.modeshape.jcr.cache.ChildReference;
import org.modeshape.jcr.cache.ChildReferences;
import org.modeshape.jcr.cache.DocumentNotFoundException;
import org.modeshape.jcr.cache.NodeKey;
import org.modeshape.jcr.cache.document.DocumentTranslator.ChildReferencesInfo;
import org.modeshape.jcr.value.Name;
import org.modeshape.jcr.value.NamespaceRegistry;

/**
 * 
 */
public class ImmutableChildReferences {

    protected static final ChildReferences EMPTY_CHILD_REFERENCES = EmptyChildReferences.INSTANCE;
    protected static final Iterator<ChildReference> EMPTY_ITERATOR = new EmptyIterator<ChildReference>();
    protected static final Iterator<NodeKey> EMPTY_KEY_ITERATOR = new EmptyIterator<NodeKey>();

    public static ChildReferences create( DocumentTranslator documentTranslator,
                                          Document document,
                                          String childrenFieldName ) {
        return new Medium(documentTranslator, document, childrenFieldName);
    }

    public static ChildReferences union( ChildReferences first, ChildReferences second ) {
        long firstSize = first.size();
        long secondSize = second.size();
        if (firstSize == 0 && secondSize == 0) {
            return EMPTY_CHILD_REFERENCES;
        } else if (firstSize == 0) {
            return second;
        } else if (secondSize == 0) {
            return first;
        }
        return new ReferencesUnion(first, second);
    }

    public static ChildReferences create( ChildReferences first,
                                          ChildReferencesInfo firstSegmentingInfo,
                                          WorkspaceCache cache ,
                                          String nodeKey ) {
        if (firstSegmentingInfo == null || firstSegmentingInfo.nextKey == null) return first;
        return new Segmented(nodeKey, cache, first, firstSegmentingInfo);
    }

    public static ChildReferences create( ChildReferences first,
                                          ChildReferencesInfo firstSegmentingInfo,
                                          ChildReferences second,
                                          WorkspaceCache cache,
                                          String nodeKey) {
        if (firstSegmentingInfo == null || firstSegmentingInfo.nextKey == null) return union(first, second);
        Segmented segmentedReferences = new Segmented(nodeKey, cache, first, firstSegmentingInfo);
        return union(segmentedReferences, second);
    }

    @Immutable
    protected final static class EmptyChildReferences implements ChildReferences {

        public static final ChildReferences INSTANCE = new EmptyChildReferences();

        private EmptyChildReferences() {
        }

        @Override
        public boolean supportsGetChildReferenceByKey() {
            return true;
        }

        @Override
        public long size() {
            return 0;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public int getChildCount( Name name ) {
            return 0;
        }

        @Override
        public ChildReference getChild( Name name ) {
            return null;
        }

        @Override
        public ChildReference getChild( Name name,
                                        int snsIndex ) {
            return null;
        }

        @Override
        public ChildReference getChild( Name name,
                                        int snsIndex,
                                        Context context ) {
            return null;
        }

        @Override
        public ChildReference getChild( org.modeshape.jcr.value.Path.Segment segment ) {
            return null;
        }

        @Override
        public ChildReference getChild( NodeKey key ) {
            return null;
        }

        @Override
        public ChildReference getChild( NodeKey key,
                                        Context context ) {
            return null;
        }

        @Override
        public boolean hasChild( NodeKey key ) {
            return false;
        }

        @Override
        public Iterator<ChildReference> iterator() {
            return EMPTY_ITERATOR;
        }

        @Override
        public Iterator<ChildReference> iterator( Name name ) {
            return EMPTY_ITERATOR;
        }

        @Override
        public Iterator<ChildReference> iterator( Name name,
                                                  Context context ) {
            return EMPTY_ITERATOR;
        }

        @Override
        public Iterator<ChildReference> iterator( Context context ) {
            return EMPTY_ITERATOR;
        }

        @Override
        public Iterator<ChildReference> iterator( Collection<?> namePatterns,
                                                  NamespaceRegistry registry ) {
            return EMPTY_ITERATOR;
        }

        @Override
        public Iterator<ChildReference> iterator( Context context,
                                                  Collection<?> namePatterns,
                                                  NamespaceRegistry registry ) {
            return EMPTY_ITERATOR;
        }

        @Override
        public Iterator<NodeKey> getAllKeys() {
            return EMPTY_KEY_ITERATOR;
        }
    }

    @Immutable
    protected static final class Medium extends AbstractChildReferences {

        private final ListMultimap<Name, ChildReference> childReferences;
        private final Map<NodeKey, ChildReference> childReferencesByKey;

        protected Medium( DocumentTranslator documentTranslator, Document document, String childrenFieldName ) {
            this.childReferences = LinkedListMultimap.create();
            this.childReferencesByKey = new HashMap<NodeKey, ChildReference>();

            final List<?> documentArray = document.getArray(childrenFieldName);
            if (documentArray == null)  {
                return;
            }
            int size = documentArray.size();
            List<ChildReference> childrenReferences = new ArrayList<ChildReference>(size);
            for (Object value : documentArray) {
                ChildReference childReference = documentTranslator.childReferenceFrom(value);
                if (childReference != null) {
                    childrenReferences.add(childReference);
                }
            }
            for (ChildReference ref : childrenReferences) {
                Name refName = ref.getName();
                ChildReference old = this.childReferencesByKey.get(ref.getKey());
                if (old != null && old.getName().equals(ref.getName())) {
                    // We already have this key/name pair, so we don't need to add it again ...
                    continue;
                }
                // We've not seen this NodeKey/Name combination yet, so it is okay. In fact, we should not see any
                // node key more than once, since that is clearly an unexpected condition (as a child may not appear
                // more than once in its parent's list of child nodes). See MODE-2120.

                //we can precompute the SNS index up front
                int currentSNS = this.childReferences.get(refName).size();
                ChildReference refWithSNS = ref.with(currentSNS + 1);
                this.childReferencesByKey.put(ref.getKey(), refWithSNS);
                this.childReferences.put(ref.getName(), refWithSNS);
            }
        }

        @Override
        public long size() {
            return childReferencesByKey.size();
        }

        @Override
        public int getChildCount( Name name ) {
            return childReferences.get(name).size();
        }

        @Override
        public ChildReference getChild( Name name,
                                        int snsIndex,
                                        Context context ) {
            Changes changes = null;
            Iterator<ChildInsertions> insertions = null;
            boolean includeRenames = false;
            if (context == null) {
                context = new SingleNameContext();
            } else {
                changes = context.changes(); // may still be null
                if (changes != null) {
                    insertions = changes.insertions(name);
                    includeRenames = changes.isRenamed(name);
                }
            }

            List<ChildReference> childrenWithSameName = this.childReferences.get(name);
            if (changes == null) {
                //there are no changes, so we can take advantage of the fact that we precomputed the SNS indexes up front...
                if (snsIndex > childrenWithSameName.size()) {
                    return null;
                }
                return childrenWithSameName.get(snsIndex - 1);
            } else {
                //there are changes, so there is some extra processing to be done...

                if (childrenWithSameName.isEmpty() && !includeRenames) {
                    // This segment contains no nodes with the supplied name ...
                    if (insertions == null) {
                        // and no nodes with this name were inserted ...
                        return null;
                    }
                    // But there is at least one inserted node with this name ...
                    while (insertions.hasNext()) {
                        ChildInsertions inserted = insertions.next();
                        Iterator<ChildReference> iter = inserted.inserted().iterator();
                        while (iter.hasNext()) {
                            ChildReference result = iter.next();
                            int index = context.consume(result.getName(), result.getKey());
                            if (index == snsIndex)
                                return result.with(index);
                        }
                    }
                    return null;
                }

                // This collection contains at least one node with the same name ...
                if (insertions != null || includeRenames) {
                    // The logic for this would involve iteration to find the indexes, so we may as well just iterate ...
                    Iterator<ChildReference> iter = iterator(context, name);
                    while (iter.hasNext()) {
                        ChildReference ref = iter.next();
                        if (ref.getSnsIndex() == snsIndex)
                            return ref;
                    }
                    return null;
                }

                // We have at least one SNS in this list (and still potentially some removals) ...
                for (ChildReference childWithSameName : childrenWithSameName) {
                    if (changes.isRemoved(childWithSameName)) { continue; }
                    if (changes.isRenamed(childWithSameName)) { continue; }
                    //we've already precomputed the SNS index
                    if (snsIndex == childWithSameName.getSnsIndex()) {
                        return childWithSameName;
                    }
                }
                return null;
            }
        }

        @Override
        public ChildReference getChild( NodeKey key,
                                        Context context ) {
            ChildReference ref = childReferencesByKey.get(key);
            if (ref == null) {
                // Not in our list, so check the context for changes ...
                if (context != null) {
                    Changes changes = context.changes();
                    if (changes != null) {
                        ref = changes.inserted(key);
                        if (ref != null) {
                            // The requested node was inserted, so figure out the SNS index.
                            // Unfortunately, this would require iteration (to figure out the indexes), so the
                            // easiest/fastest way is (surprisingly) to just iterate ...
                            Iterator<ChildReference> iter = iterator(context, ref.getName());
                            while (iter.hasNext()) {
                                ChildReference child = iter.next();
                                if (child.getKey().equals(key)) return child;
                            }
                            // Shouldn't really happen ...
                        }
                    }
                    // Not in list and no changes, so return ...
                }
            } else {
                // It's in our list, but if there are changes we need to use the context ...
                if (context != null) {
                    Changes changes = context.changes();
                    if (changes != null) {
                        boolean renamed = false;
                        if (changes.isRenamed(ref)) {
                            // The node was renamed, so get the new name ...
                            Name newName = changes.renamed(key);
                            ref = ref.with(newName, 1);
                            renamed = true;
                        }
                        Iterator<ChildInsertions> insertions = changes.insertions(ref.getName());
                        if (insertions != null || renamed) {
                            // We're looking for a node that was not inserted but has the same name as those that are
                            // (perhaps have renaming). As painful as it is, the easiest and most-efficient way to get
                            // this is to iterate ...
                            Iterator<ChildReference> iter = iterator(context, ref.getName());
                            while (iter.hasNext()) {
                                ChildReference child = iter.next();
                                if (child.getKey().equals(key)) return child;
                            }
                        }

                        // check if the node was removed only at the end to that insertions before can be processed first
                        if (changes.isRemoved(ref)) {
                            // The node was removed ...
                            return null;
                        }
                    } else {
                        // It's in our list but there are no changes so we can optimize this based on the fact that we already
                        // precomputed the SNS indexes up front
                        return childReferencesByKey.get(ref.getKey());
                    }
                }
            }
            return ref;
        }

        @Override
        public ChildReference getChild( NodeKey key ) {
            //we should already have precomputed the correct SNS at the beginning
            return childReferencesByKey.get(key);
        }

        @Override
        public boolean hasChild( NodeKey key ) {
            return childReferencesByKey.containsKey(key);
        }

        @Override
        public Iterator<ChildReference> iterator( Name name ) {
            //the child references should already have the correct SNS precomputed
            return childReferences.get(name).iterator();
        }

        @Override
        public Iterator<ChildReference> iterator() {
            //the child references should already have the correct SNS precomputed
            return childReferences.values().iterator();
        }


        @Override
        public Iterator<ChildReference> iterator( Name name, Context context ) {
            if (context != null && context.changes() != null) {
                //we only want the context-sensitive behavior if there are changes. Otherwise we've already precomputed the SNS index
                return super.iterator(name, context);
            }
            return iterator(name);
        }

        @Override
        public Iterator<ChildReference> iterator( Context context ) {
            if (context != null && context.changes() != null) {
                //we only want the context-sensitive behavior if there are changes. Otherwise we've already precomputed the SNS index
                return super.iterator(context);
            }
            return iterator();
        }

        @Override
        public Iterator<NodeKey> getAllKeys() {
            return childReferencesByKey.keySet().iterator();
        }

        @Override
        public StringBuilder toString( StringBuilder sb ) {
            Iterator<ChildReference> iter = childReferences.values().iterator();
            if (iter.hasNext()) {
                sb.append(iter.next());
                while (iter.hasNext()) {
                    sb.append(", ");
                    sb.append(iter.next());
                }
            }
            return sb;
        }
    }

    @Immutable
    public static class Segmented extends AbstractChildReferences {

        protected final WorkspaceCache cache;
        protected final long totalSize;
        private Segment firstSegment;
        private String nodeKey;

        public Segmented( String nodeKey,
                          WorkspaceCache cache,
                          ChildReferences firstSegment,
                          ChildReferencesInfo info ) {
            this.nodeKey = nodeKey;
            this.cache = cache;
            this.totalSize = info.totalSize;
            this.firstSegment = new Segment(firstSegment, info.nextKey);
        }

        @Override
        public long size() {
            return totalSize;
        }

        @Override
        public boolean supportsGetChildReferenceByKey() {
            return size() != ChildReferences.UNKNOWN_SIZE;
        }

        @Override
        public int getChildCount( Name name ) {

            // enhanced logic
            if (size() == ChildReferences.UNKNOWN_SIZE) {
                DocumentStore documentStore = cache.documentStore();
                int childCount = documentStore.getChildCount(nodeKey, name);
                if (childCount >= 0 ) return childCount;
            }

            // default logic
            int result = 0;
            Segment segment = this.firstSegment;
            while (segment != null) {
                result += segment.getReferences().getChildCount(name);
                segment = segment.next(cache);
            }
            return result;
        }

        @Override
        public ChildReference getChild( Name name,
                                        int snsIndex,
                                        Context context ) {

            if (size() == ChildReferences.UNKNOWN_SIZE) {
                ChildReference childReference = cache.documentStore().getChildReferenceAsRef(nodeKey, name, snsIndex);
                if (childReference != null)
                    return childReference;
            }

            ChildReference result = null;
            Segment segment = this.firstSegment;
            while (segment != null) {
                result = segment.getReferences().getChild(name, snsIndex, context);
                if (result != null) {
                    return result;
                }
                segment = segment.next(cache);
            }
            return result;
        }

        @Override
        public boolean hasChild( NodeKey key ) {
            Segment segment = this.firstSegment;
            while (segment != null) {
                if (segment.getReferences().hasChild(key)) {
                    return true;
                }
                segment = segment.next(cache);
            }
            return false;
        }

        @Override
        public ChildReference getChild( NodeKey key ) {
            return getChild(key, new BasicContext());
        }

        @Override
        public ChildReference getChild( NodeKey key,
                                        Context context ) {
            // enhanced logic
            if (size() == ChildReferences.UNKNOWN_SIZE) {
                ChildReference childReference = cache.documentStore().getChildReferenceAsRef(nodeKey, key.toString());
                if (childReference != null)
                    return childReference;
            }

            ChildReference result = null;
            Segment segment = this.firstSegment;
            while (segment != null) {
                result = segment.getReferences().getChild(key, context);
                if (result != null) {
                    return result;
                }
                segment = segment.next(cache);
            }
            return result;
        }

        @Override
        public Iterator<ChildReference> iterator( final Name name,
                                                  final Context context ) {
            final Segment firstSegment = this.firstSegment;
            return new Iterator<ChildReference>() {
                private Segment segment = firstSegment;
                private Iterator<ChildReference> iter = segment != null ? segment.getReferences().iterator(name, context) : ImmutableChildReferences.EMPTY_ITERATOR;
                private ChildReference next;

                @Override
                public boolean hasNext() {
                    if (next != null) {
                        // Called 'hasNext()' multiple times in a row ...
                        return true;
                    }
                    if (!iter.hasNext()) {
                        while (segment != null) {
                            segment = segment.next(cache);
                            if (segment != null) {
                                iter = segment.getReferences().iterator(name, context);
                                if (iter.hasNext()) {
                                    next = iter.next();
                                    return true;
                                }
                            }
                        }
                        return false;
                    }
                    next = iter.next();
                    return true;
                }

                @Override
                public ChildReference next() {
                    try {
                        if (next == null) {
                            if (hasNext()) return next;
                            throw new NoSuchElementException();
                        }
                        return next;
                    } finally {
                        next = null;
                    }
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public Iterator<ChildReference> iterator( final Context context ) {
            final Segment firstSegment = this.firstSegment;
            return new Iterator<ChildReference>() {
                private Segment segment = firstSegment;
                private Iterator<ChildReference> iter = segment != null ? segment.getReferences().iterator(context) : ImmutableChildReferences.EMPTY_ITERATOR;
                private ChildReference next;

                @Override
                public boolean hasNext() {
                    if (next != null) {
                        // Called 'hasNext()' multiple times in a row ...
                        return true;
                    }
                    if (!iter.hasNext()) {
                        while (segment != null) {
                            segment = segment.next(cache);
                            if (segment != null) {
                                iter = segment.getReferences().iterator(context);
                                if (iter.hasNext()) {
                                    next = iter.next();
                                    return true;
                                }
                            }
                        }
                        return false;
                    }
                    next = iter.next();
                    return true;
                }

                @Override
                public ChildReference next() {
                    try {
                        if (next == null) {
                            if (hasNext()) return next;
                            throw new NoSuchElementException();
                        }
                        return next;
                    } finally {
                        next = null;
                    }
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public Iterator<NodeKey> getAllKeys() {
            final Segment firstSegment = this.firstSegment;
            return new Iterator<NodeKey>() {
                private Segment segment = firstSegment;
                private Iterator<NodeKey> iter = segment != null ? segment.keys() : ImmutableChildReferences.EMPTY_KEY_ITERATOR;
                private NodeKey next;

                @Override
                public boolean hasNext() {
                    if (!iter.hasNext()) {
                        while (segment != null) {
                            segment = segment.next(cache);
                            if (segment != null) {
                                iter = segment.keys();
                                if (iter.hasNext()) {
                                    next = iter.next();
                                    return true;
                                }
                            }
                        }
                        return false;
                    }
                    next = iter.next();
                    return true;
                }

                @Override
                public NodeKey next() {
                    try {
                        if (next == null) {
                            if (hasNext()) return next;
                            throw new NoSuchElementException();
                        }
                        return next;
                    } finally {
                        next = null;
                    }
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @SuppressWarnings( "synthetic-access" )
        @Override
        public StringBuilder toString( StringBuilder sb ) {
            Segment segment = firstSegment;
            while (segment != null) {
                segment.toString(sb);
                if (segment.next != null) {
                    // there is already a loaded segment ...
                    sb.append(", ");
                } else if (segment.nextKey != null) {
                    // there is a segment, but it hasn't yet been loaded ...
                    sb.append(", <segment=").append(segment.nextKey).append('>');
                }
                segment = segment.next;
            }
            return sb;
        }
    }

    public static class ReferencesUnion extends AbstractChildReferences {

        private final ChildReferences firstReferences;
        private final ChildReferences secondReferences;

        ReferencesUnion( ChildReferences firstReferences, ChildReferences secondReferences ) {
            this.firstReferences = firstReferences;
            this.secondReferences = secondReferences;
        }

        @Override
        public long size() {
            return secondReferences.size() + firstReferences.size();
        }

        @Override
        public int getChildCount( Name name ) {
            return secondReferences.getChildCount(name) + firstReferences.getChildCount(name);
        }

        @Override
        public ChildReference getChild( Name name,
                                        int snsIndex,
                                        Context context ) {
            ChildReference firstReferencesChild = firstReferences.getChild(name, snsIndex, context);
            if (firstReferencesChild != null) {
                return firstReferencesChild;
            }
            return secondReferences.getChild(name, snsIndex, context);
        }

        @Override
        public boolean hasChild( NodeKey key ) {
            return secondReferences.hasChild(key) || firstReferences.hasChild(key);
        }

        @Override
        public ChildReference getChild( NodeKey key ) {
            return getChild(key, new BasicContext());
        }

        @Override
        public ChildReference getChild( NodeKey key,
                                        Context context ) {
            ChildReference firstReferencesChild = firstReferences.getChild(key, context);
            if (firstReferencesChild != null) {
                return firstReferencesChild;
            }
            return secondReferences.getChild(key, context);
        }

        @Override
        public Iterator<ChildReference> iterator() {
            return new UnionIterator<ChildReference>(firstReferences.iterator(), secondReferences);
        }

        @Override
        public Iterator<ChildReference> iterator( final Name name ) {
            Iterable<ChildReference> second = new Iterable<ChildReference>() {
                @Override
                public Iterator<ChildReference> iterator() {
                    return secondReferences.iterator(name);
                }
            };
            return new UnionIterator<ChildReference>(firstReferences.iterator(name), second);
        }

        @Override
        public Iterator<NodeKey> getAllKeys() {
            Set<NodeKey> externalKeys = new HashSet<NodeKey>();
            for (Iterator<NodeKey> externalKeysIterator = secondReferences.getAllKeys(); externalKeysIterator.hasNext();) {
                externalKeys.add(externalKeysIterator.next());
            }
            return new UnionIterator<NodeKey>(firstReferences.getAllKeys(), externalKeys);
        }

        @Override
        public StringBuilder toString( StringBuilder sb ) {
            sb.append("<second references=").append(secondReferences.toString());
            sb.append(">, <first references=").append(firstReferences.toString()).append(">");
            return sb;
        }
    }

    protected static class Segment {

        private final ChildReferences references;
        private final String nextKey;
        private Segment next;

        protected Segment( ChildReferences references,
                           String nextKey ) {
            this.nextKey = nextKey;
            this.references = references;
        }

        public ChildReferences getReferences() {
            return this.references;
        }

        public Segment next( WorkspaceCache cache ) {
            if (next == null && nextKey != null) {
                Document blockDoc = cache.blockFor(nextKey);
                if (blockDoc == null) {
                    throw new DocumentNotFoundException(nextKey);
                }
                // we only need the direct children of the block to avoid nesting
                ChildReferences refs = cache.translator().getChildReferencesFromBlock(blockDoc);
                ChildReferencesInfo nextNextKey = cache.translator().getChildReferencesInfo(blockDoc);
                next = new Segment(refs, nextNextKey != null ? nextNextKey.nextKey : null);
            }
            return next;
        }

        public Iterator<NodeKey> keys() {
            return references.getAllKeys();
        }

        @Override
        public String toString() {
            return toString(new StringBuilder()).toString();
        }

        public StringBuilder toString( StringBuilder sb ) {
            Iterator<ChildReference> iter = references.iterator();
            if (iter.hasNext()) {
                sb.append(iter.next());
                while (iter.hasNext()) {
                    sb.append(", ");
                    sb.append(iter.next());
                }
            }
            return sb;
        }
    }
}

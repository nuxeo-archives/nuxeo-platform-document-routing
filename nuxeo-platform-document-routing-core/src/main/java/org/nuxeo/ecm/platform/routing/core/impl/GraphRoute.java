/*
 * (C) Copyright 2012 Nuxeo SA (http://nuxeo.com/) and contributors.
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
package org.nuxeo.ecm.platform.routing.core.impl;

import org.nuxeo.ecm.platform.routing.api.exception.DocumentRouteException;

/**
 * A route graph, defining a workflow with arbitrarily complex transitions
 * between nodes.
 *
 * @since 5.6
 */
public interface GraphRoute {

    /**
     * Gets the name for this graph.
     *
     * @return the name
     */
    String getName();

    /**
     * Gets the start node for this graph.
     *
     * @return the start node
     */
    GraphNode getStartNode() throws DocumentRouteException;

}

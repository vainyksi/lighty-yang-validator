/*
 * Copyright (c) 2021 Pantheon Technologies s.r.o. All Rights Reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v10.html
 */
package io.lighty.yang.validator.formats;

import static java.lang.Math.min;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.lighty.yang.validator.GroupArguments;
import io.lighty.yang.validator.config.Configuration;
import io.lighty.yang.validator.exceptions.NotFoundException;
import io.lighty.yang.validator.formats.utility.LyvNodeData;
import io.lighty.yang.validator.simplify.SchemaTree;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.sourceforge.argparse4j.impl.choice.CollectionArgumentChoice;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.model.api.ActionDefinition;
import org.opendaylight.yangtools.yang.model.api.ActionNodeContainer;
import org.opendaylight.yangtools.yang.model.api.CaseSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ChoiceSchemaNode;
import org.opendaylight.yangtools.yang.model.api.DataNodeContainer;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ListSchemaNode;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.NotificationDefinition;
import org.opendaylight.yangtools.yang.model.api.RpcDefinition;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaNode;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.opendaylight.yangtools.yang.model.repo.api.RevisionSourceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Tree extends FormatPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(Tree.class);
    private static final String HELP_NAME = "tree";
    private static final String HELP_DESCRIPTION = "Prints out tree of the modules";
    private static final String MODULE = "module: ";
    private static final String AUGMENT = "augment ";
    private static final String SLASH = "/";
    private static final String COLON = ":";
    private static final String RPCS = "RPCs:";
    private static final String NOTIFICATION = "notifications:";

    private final Map<URI, String> namespacePrefix = new HashMap<>();
    private Module usedModule = null;
    private int treeDepth;
    private int lineLength;

    @Override
    void init(final SchemaContext context, final List<RevisionSourceIdentifier> testFilesSchemaSources,
            final SchemaTree schemaTree, final Configuration config) {
        super.init(context, testFilesSchemaSources, schemaTree, config);
        treeDepth = this.configuration.getTreeConfiguration().getTreeDepth();
        final int len = this.configuration.getTreeConfiguration().getLineLength();
        this.lineLength = len == 0 ? 10000 : len;
    }

    @Override
    @SuppressFBWarnings(value = "SLF4J_SIGN_ONLY_FORMAT",
                        justification = "Valid output from LYV is dependent on Logback output")
    public void emitFormat() {
        if (this.configuration.getTreeConfiguration().isHelp()) {
            printHelp();
        }
        for (final RevisionSourceIdentifier source : this.sources) {
            usedModule = this.schemaContext.findModule(source.getName(), source.getRevision())
                    .orElseThrow(() -> new NotFoundException("Module", source.getName()));
            final String firstLine = MODULE + usedModule.getName();
            LOG.info("{}", firstLine.substring(0, min(firstLine.length(), lineLength)));

            putSchemaContextModuleMatchedWithUsedModuleToNamespacePrefix();

            AtomicInteger rootNodes = new AtomicInteger(0);
            for (Map.Entry<SchemaPath, SchemaTree> st : this.schemaTree.getChildren().entrySet()) {
                if (st.getKey().getLastComponent().getModule().equals(usedModule.getQNameModule())
                        && !st.getValue().isAugmenting()) {
                    rootNodes.incrementAndGet();
                }
            }
            final List<Integer> removeChoiceQnames = new ArrayList<>();

            // Nodes
            printLines(getSchemaNodeLines(rootNodes, removeChoiceQnames));

            // Augmentations
            final Map<List<QName>, Set<SchemaTree>> augments = getAugmentationMap();
            for (Map.Entry<List<QName>, Set<SchemaTree>> st : augments.entrySet()) {
                printLines(getAugmentedLines(st, removeChoiceQnames));
            }

            // Rpcs
            final Iterator<? extends RpcDefinition> rpcs = usedModule.getRpcs().iterator();
            if (rpcs.hasNext()) {
                LOG.info("{}", RPCS.substring(0, min(RPCS.length(), lineLength)));
            }
            printLines(getRpcsLines(rpcs, removeChoiceQnames));

            // Notifications
            final Iterator<? extends NotificationDefinition> notifications = usedModule.getNotifications().iterator();
            if (notifications.hasNext()) {
                LOG.info("{}", NOTIFICATION.substring(0, min(NOTIFICATION.length(), lineLength)));
            }
            printLines(getNotificationLines(notifications, removeChoiceQnames));
        }
    }

    @SuppressFBWarnings(value = "SLF4J_SIGN_ONLY_FORMAT",
                        justification = "Valid output from LYV is dependent on Logback output")
    private List<Line> getAugmentedLines(Entry<List<QName>, Set<SchemaTree>> st, List<Integer> removeChoiceQnames) {
        List<Line> lines = new ArrayList<>();
        final StringBuilder pathBuilder = new StringBuilder();
        for (QName qname : st.getKey()) {
            pathBuilder.append(SLASH);
            if (this.configuration.getTreeConfiguration().isPrefixMainModule()
                    || namespacePrefix.containsKey(qname.getNamespace())) {
                pathBuilder.append(namespacePrefix.get(qname.getNamespace()))
                        .append(COLON);
            }
            pathBuilder.append(qname.getLocalName());
        }
        final String augmentText = AUGMENT + pathBuilder.append(COLON).toString();
        LOG.info("{}", augmentText.substring(0, min(augmentText.length(), lineLength)));
        int augmentationNodes = st.getValue().size();
        for (final SchemaTree value : st.getValue()) {
            DataSchemaNode node = value.getSchemaNode();
            LyvNodeData lyvNodeData = new LyvNodeData(this.schemaContext, node, Collections.emptyList());
            ConsoleLine consoleLine = new ConsoleLine(Collections.emptyList(), lyvNodeData, RpcInputOutput.OTHER,
                    removeChoiceQnames, namespacePrefix);
            lines.add(consoleLine);
            resolveChildNodes(lines, new ArrayList<>(), value, --augmentationNodes > 0,
                    RpcInputOutput.OTHER, removeChoiceQnames, Collections.emptyList());
            this.treeDepth++;
        }
        return lines;
    }

    private List<Line> getSchemaNodeLines(AtomicInteger rootNodes, List<Integer> removeChoiceQnames) {
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<SchemaPath, SchemaTree> st : this.schemaTree.getChildren().entrySet()) {
            if (st.getKey().getLastComponent().getModule().equals(usedModule.getQNameModule())
                    && !st.getValue().isAugmenting()) {
                DataSchemaNode node = st.getValue().getSchemaNode();
                LyvNodeData lyvNodeData = new LyvNodeData(this.schemaContext, node, Collections.emptyList());
                ConsoleLine consoleLine = new ConsoleLine(Collections.emptyList(), lyvNodeData, RpcInputOutput.OTHER,
                                removeChoiceQnames, namespacePrefix);
                lines.add(consoleLine);
                List<QName> keyDefinitions = Collections.emptyList();
                if (node instanceof ListSchemaNode) {
                    keyDefinitions = ((ListSchemaNode) node).getKeyDefinition();
                }
                resolveChildNodes(lines, new ArrayList<>(), st.getValue(), rootNodes.decrementAndGet() > 0,
                        RpcInputOutput.OTHER, removeChoiceQnames, keyDefinitions);
                this.treeDepth++;
            }
        }
        return lines;
    }

    private void putSchemaContextModuleMatchedWithUsedModuleToNamespacePrefix() {
        for (Module m : this.schemaContext.getModules()) {
            if (!m.getPrefix().equals(usedModule.getPrefix())
                    || this.configuration.getTreeConfiguration().isPrefixMainModule()) {
                if (this.configuration.getTreeConfiguration().isModulePrefix()) {
                    namespacePrefix.put(m.getNamespace(), m.getName());
                } else {
                    namespacePrefix.put(m.getNamespace(), m.getPrefix());
                }
            }
        }
    }

    private List<Line> getNotificationLines(final Iterator<? extends NotificationDefinition> notifications,
            final List<Integer> removeChoiceQnames) {
        List<Line> lines = new ArrayList<>();
        while (notifications.hasNext()) {
            final NotificationDefinition node = notifications.next();
            LyvNodeData lyvNodeData = new LyvNodeData(this.schemaContext, node, Collections.emptyList());
            ConsoleLine consoleLine = new ConsoleLine(Collections.emptyList(), lyvNodeData, RpcInputOutput.OTHER,
                    removeChoiceQnames, namespacePrefix);
            lines.add(consoleLine);
            resolveChildNodes(lines, new ArrayList<>(), node, false, RpcInputOutput.OTHER,
                    removeChoiceQnames, Collections.emptyList());
            this.treeDepth++;
        }
        return lines;
    }

    private List<Line> getRpcsLines(Iterator<? extends RpcDefinition> rpcs, final List<Integer> removeChoiceQnames) {
        List<Line> lines = new ArrayList<>();
        while (rpcs.hasNext()) {
            final RpcDefinition node = rpcs.next();
            LyvNodeData lyvNodeData = new LyvNodeData(this.schemaContext, node, Collections.emptyList());
            ConsoleLine consoleLine = new ConsoleLine(Collections.emptyList(), lyvNodeData, RpcInputOutput.OTHER,
                    removeChoiceQnames, namespacePrefix);
            lines.add(consoleLine);
            final boolean inputExists = !node.getInput().getChildNodes().isEmpty();
            final boolean outputExists = !node.getOutput().getChildNodes().isEmpty();
            if (inputExists) {
                lyvNodeData = new LyvNodeData(this.schemaContext, node.getInput(), Collections.emptyList());
                consoleLine = new ConsoleLine(Collections.singletonList(rpcs.hasNext()), lyvNodeData,
                        RpcInputOutput.INPUT, removeChoiceQnames, namespacePrefix);
                lines.add(consoleLine);
                final ArrayList<Boolean> isNextRpc = new ArrayList<>(Collections.singleton(rpcs.hasNext()));
                resolveChildNodes(lines, isNextRpc, node.getInput(), outputExists, RpcInputOutput.INPUT,
                        removeChoiceQnames, Collections.emptyList());
                this.treeDepth++;
            }
            if (outputExists) {
                lyvNodeData = new LyvNodeData(this.schemaContext, node.getOutput(), Collections.emptyList());
                consoleLine = new ConsoleLine(Collections.singletonList(rpcs.hasNext()), lyvNodeData,
                        RpcInputOutput.OUTPUT, removeChoiceQnames, namespacePrefix);
                lines.add(consoleLine);
                final ArrayList<Boolean> isNextRpc = new ArrayList<>(Collections.singleton(rpcs.hasNext()));
                resolveChildNodes(lines, isNextRpc, node.getOutput(), false, RpcInputOutput.OUTPUT,
                        removeChoiceQnames, Collections.emptyList());
                this.treeDepth++;
            }
        }
        return lines;
    }

    private Map<List<QName>, Set<SchemaTree>> getAugmentationMap() {
        final Map<List<QName>, Set<SchemaTree>> augments = new LinkedHashMap<>();
        for (Map.Entry<SchemaPath, SchemaTree> st : this.schemaTree.getChildren().entrySet()) {
            if (st.getKey().getLastComponent().getModule().equals(usedModule.getQNameModule())
                    && st.getValue().isAugmenting()) {
                final Iterator<QName> iterator = st.getKey().getPathFromRoot().iterator();
                List<QName> qnames = new ArrayList<>();
                while (iterator.hasNext()) {
                    final QName next = iterator.next();
                    if (iterator.hasNext()) {
                        qnames.add(next);
                    }
                }
                if (augments.get(qnames) == null || augments.get(qnames).isEmpty()) {
                    augments.put(qnames, new LinkedHashSet<>());
                }
                augments.get(qnames).add(st.getValue());
            }
        }
        return augments;
    }

    private void resolveChildNodes(final List<Line> lines, final List<Boolean> isConnected, final SchemaTree st,
            final boolean hasNext, final RpcInputOutput inputOutput, final List<Integer> removeChoiceQnames,
            final List<QName> keys) {
        if (--this.treeDepth == 0) {
            return;
        }
        boolean actionExists = false;
        final DataSchemaNode node = st.getSchemaNode();
        if (node instanceof ActionNodeContainer) {
            actionExists = !((ActionNodeContainer) node).getActions().isEmpty();
        }
        if (node instanceof DataNodeContainer) {
            isConnected.add(hasNext);
            resolveDataNodeContainer(lines, isConnected, st, inputOutput, removeChoiceQnames, keys, actionExists);
            isConnected.remove(isConnected.size() - 1);
        } else if (node instanceof ChoiceSchemaNode) {
            isConnected.add(hasNext);
            resolveChoiceSchemaNode(lines, isConnected, st, inputOutput, removeChoiceQnames, actionExists, node);
            isConnected.remove(isConnected.size() - 1);
        }
        // If action is in container or list
        if (!st.getActionDefinitionChildren().isEmpty()) {
            isConnected.add(hasNext);
            final Iterator<Map.Entry<SchemaPath, SchemaTree>> actions =
                    st.getActionDefinitionChildren().entrySet().iterator();
            while (actions.hasNext()) {
                resolveActions(lines, isConnected, hasNext, removeChoiceQnames, actions);
                isConnected.remove(isConnected.size() - 1);
            }
        }
    }

    private void resolveChildNodes(final List<Line> lines, final List<Boolean> isConnected, final SchemaNode node,
            final boolean hasNext, final RpcInputOutput inputOutput, final List<Integer> removeChoiceQnames,
            final List<QName> keys) {
        if (--this.treeDepth == 0) {
            return;
        }

        boolean actionExists = false;
        if (node instanceof ActionNodeContainer) {
            actionExists = !((ActionNodeContainer) node).getActions().isEmpty();
        }
        if (node instanceof DataNodeContainer) {
            isConnected.add(hasNext);
            resolveDataNodeContainer(lines, isConnected, node, inputOutput, removeChoiceQnames, keys, actionExists);
            // remove last
            isConnected.remove(isConnected.size() - 1);
        } else if (node instanceof ChoiceSchemaNode) {
            isConnected.add(hasNext);
            resolveChoiceSchemaNode(lines, isConnected, node, inputOutput, removeChoiceQnames, actionExists);
            // remove last
            isConnected.remove(isConnected.size() - 1);
        }
        // If action is in container or list
        if (node instanceof ActionNodeContainer) {
            final Iterator<? extends ActionDefinition> actions = ((ActionNodeContainer) node).getActions().iterator();
            while (actions.hasNext()) {
                final ActionDefinition action = actions.next();
                isConnected.add(actions.hasNext());
                LyvNodeData lyvNodeData = new LyvNodeData(this.schemaContext, action, Collections.emptyList());
                ConsoleLine consoleLine = new ConsoleLine(new ArrayList<>(isConnected), lyvNodeData,
                        RpcInputOutput.OTHER, removeChoiceQnames, namespacePrefix);
                lines.add(consoleLine);
                final boolean inputExists = !action.getInput().getChildNodes().isEmpty();
                final boolean outputExists = !action.getOutput().getChildNodes().isEmpty();
                if (inputExists) {
                    isConnected.add(outputExists);
                    lyvNodeData = new LyvNodeData(this.schemaContext, action.getInput(), Collections.emptyList());
                    consoleLine = new ConsoleLine(new ArrayList<>(isConnected), lyvNodeData, RpcInputOutput.INPUT,
                            removeChoiceQnames, namespacePrefix);
                    lines.add(consoleLine);
                    resolveChildNodes(lines, isConnected, action.getInput(), outputExists, RpcInputOutput.INPUT,
                            removeChoiceQnames, Collections.emptyList());
                    this.treeDepth++;
                    isConnected.remove(isConnected.size() - 1);
                }
                if (outputExists) {
                    isConnected.add(false);
                    lyvNodeData = new LyvNodeData(this.schemaContext, action.getOutput(), Collections.emptyList());
                    consoleLine = new ConsoleLine(new ArrayList<>(isConnected), lyvNodeData,
                            RpcInputOutput.OUTPUT, removeChoiceQnames, namespacePrefix);
                    lines.add(consoleLine);
                    resolveChildNodes(lines, isConnected, action.getOutput(), false, RpcInputOutput.OUTPUT,
                            removeChoiceQnames, Collections.emptyList());
                    this.treeDepth++;
                    isConnected.remove(isConnected.size() - 1);
                }
                isConnected.remove(isConnected.size() - 1);
            }
        }
    }

    private void resolveActions(final List<Line> lines, final List<Boolean> isConnected, boolean hasNext,
            final List<Integer> removeChoiceQnames, final Iterator<Map.Entry<SchemaPath, SchemaTree>> actions) {
        final Map.Entry<SchemaPath, SchemaTree> nextST = actions.next();
        if (nextST.getKey().getLastComponent().getModule().equals(usedModule.getQNameModule())) {
            final SchemaTree actionSchemaTree = nextST.getValue();
            resolveActions(lines, isConnected, hasNext, removeChoiceQnames, actions, actionSchemaTree);
        }
    }

    private void resolveActions(final List<Line> lines, final List<Boolean> isConnected, boolean hasNext,
            final List<Integer> removeChoiceQnames, final Iterator<Map.Entry<SchemaPath, SchemaTree>> actions,
            final SchemaTree actionSchemaTree) {
        final ActionDefinition action = actionSchemaTree.getActionNode();
        LyvNodeData lyvNodeData = new LyvNodeData(this.schemaContext, action, Collections.emptyList());
        ConsoleLine consoleLine = new ConsoleLine(new ArrayList<>(isConnected), lyvNodeData, RpcInputOutput.OTHER,
                removeChoiceQnames, namespacePrefix);
        lines.add(consoleLine);
        boolean inputExists = false;
        boolean outputExists = false;
        SchemaTree inValue = null;
        SchemaTree outValue = null;
        for (Map.Entry<SchemaPath, SchemaTree> inOut : actionSchemaTree.getChildren().entrySet()) {
            if ("input".equals(inOut.getKey().getLastComponent().getLocalName())
                    && !inOut.getValue().getChildren().isEmpty()) {
                inputExists = true;
                inValue = inOut.getValue();
            } else if ("output".equals(inOut.getKey().getLastComponent().getLocalName())
                    && !inOut.getValue().getChildren().isEmpty()) {
                outputExists = true;
                outValue = inOut.getValue();
            }
        }
        if (inputExists) {
            isConnected.add(actions.hasNext() || hasNext);
            lyvNodeData = new LyvNodeData(this.schemaContext, action.getInput(), Collections.emptyList());
            consoleLine = new ConsoleLine(new ArrayList<>(isConnected), lyvNodeData, RpcInputOutput.INPUT,
                    removeChoiceQnames, namespacePrefix);
            lines.add(consoleLine);
            resolveChildNodes(lines, isConnected, inValue, outputExists, RpcInputOutput.INPUT, removeChoiceQnames,
                    Collections.emptyList());
            this.treeDepth++;
            isConnected.remove(isConnected.size() - 1);
        }
        if (outputExists) {
            isConnected.add(actions.hasNext() || hasNext);
            lyvNodeData = new LyvNodeData(this.schemaContext, action.getOutput(), Collections.emptyList());
            consoleLine = new ConsoleLine(new ArrayList<>(isConnected), lyvNodeData, RpcInputOutput.OUTPUT,
                    removeChoiceQnames, namespacePrefix);
            lines.add(consoleLine);
            resolveChildNodes(lines, isConnected, outValue, false, RpcInputOutput.OUTPUT, removeChoiceQnames,
                    Collections.emptyList());
            this.treeDepth++;
            isConnected.remove(isConnected.size() - 1);
        }
    }

    private void resolveChoiceSchemaNode(final List<Line> lines, final List<Boolean> isConnected, final SchemaTree st,
            final RpcInputOutput inputOutput, final List<Integer> removeChoiceQnames, final boolean actionExists,
            final DataSchemaNode node) {
        final Iterator<Map.Entry<SchemaPath, SchemaTree>> caseNodes
                = st.getDataSchemaNodeChildren().entrySet().iterator();
        removeChoiceQnames.add(((List) node.getPath().getPathFromRoot()).size() - 1);
        while (caseNodes.hasNext()) {
            final Map.Entry<SchemaPath, SchemaTree> nextST = caseNodes.next();
            if (nextST.getKey().getLastComponent().getModule().equals(usedModule.getQNameModule())) {
                final SchemaTree caseValue = nextST.getValue();
                final DataSchemaNode child = caseValue.getSchemaNode();
                removeChoiceQnames.add(((List) child.getPath().getPathFromRoot()).size() - 1);
                LyvNodeData lyvNodeData = new LyvNodeData(this.schemaContext, child, Collections.emptyList());
                ConsoleLine consoleLine = new ConsoleLine(new ArrayList<>(isConnected), lyvNodeData, inputOutput,
                        removeChoiceQnames, namespacePrefix);
                lines.add(consoleLine);
                resolveChildNodes(lines, isConnected, caseValue, caseNodes.hasNext()
                        || actionExists, inputOutput, removeChoiceQnames, Collections.emptyList());
                this.treeDepth++;
                removeChoiceQnames.remove(Integer.valueOf(((List) child.getPath().getPathFromRoot()).size() - 1));
            }
        }
        removeChoiceQnames.remove(Integer.valueOf(((List) node.getPath().getPathFromRoot()).size() - 1));
    }

    private void resolveChoiceSchemaNode(final List<Line> lines, final List<Boolean> isConnected, final SchemaNode node,
            final RpcInputOutput inputOutput, final List<Integer> removeChoiceQnames, final boolean actionExists) {
        final Collection<? extends CaseSchemaNode> cases = ((ChoiceSchemaNode) node).getCases();
        final Iterator<? extends CaseSchemaNode> iterator = cases.iterator();
        removeChoiceQnames.add(((List) node.getPath().getPathFromRoot()).size() - 1);
        while (iterator.hasNext()) {
            final DataSchemaNode child = iterator.next();
            removeChoiceQnames.add(((List) child.getPath().getPathFromRoot()).size() - 1);
            LyvNodeData lyvNodeData = new LyvNodeData(this.schemaContext, child, Collections.emptyList());
            ConsoleLine consoleLine = new ConsoleLine(new ArrayList<>(isConnected), lyvNodeData, inputOutput,
                    removeChoiceQnames, namespacePrefix);
            lines.add(consoleLine);
            resolveChildNodes(lines, isConnected, child, iterator.hasNext() || actionExists, inputOutput,
                    removeChoiceQnames, Collections.emptyList());
            this.treeDepth++;
            removeChoiceQnames.remove(Integer.valueOf(((List) child.getPath().getPathFromRoot()).size() - 1));
        }
        removeChoiceQnames.remove(Integer.valueOf(((List) node.getPath().getPathFromRoot()).size() - 1));
    }


    private void resolveDataNodeContainer(final List<Line> lines, final List<Boolean> isConnected, final SchemaTree st,
            final RpcInputOutput inputOutput, final List<Integer> removeChoiceQnames, final List<QName> keys,
            final boolean actionExists) {
        final Iterator<Map.Entry<SchemaPath, SchemaTree>> childNodes =
                st.getDataSchemaNodeChildren().entrySet().iterator();
        while (childNodes.hasNext()) {
            final Map.Entry<SchemaPath, SchemaTree> nextST = childNodes.next();
            if (nextST.getKey().getLastComponent().getModule().equals(usedModule.getQNameModule())) {
                final SchemaTree childSchemaTree = nextST.getValue();
                final DataSchemaNode child = childSchemaTree.getSchemaNode();
                LyvNodeData lyvNodeData = new LyvNodeData(this.schemaContext, child, keys);
                ConsoleLine consoleLine = new ConsoleLine(new ArrayList<>(isConnected), lyvNodeData, inputOutput,
                        removeChoiceQnames, namespacePrefix);
                lines.add(consoleLine);
                List<QName> keyDefinitions = Collections.emptyList();
                if (child instanceof ListSchemaNode) {
                    keyDefinitions = ((ListSchemaNode) child).getKeyDefinition();
                }
                resolveChildNodes(lines, isConnected, childSchemaTree, childNodes.hasNext()
                        || actionExists, inputOutput, removeChoiceQnames, keyDefinitions);
                this.treeDepth++;
            }
        }
    }

    private void resolveDataNodeContainer(final List<Line> lines, final List<Boolean> isConnected,
            final SchemaNode node, final RpcInputOutput inputOutput, final List<Integer> removeChoiceQnames,
            final List<QName> keys, final boolean actionExists) {
        final Iterator<? extends DataSchemaNode> childNodes = ((DataNodeContainer) node).getChildNodes().iterator();
        while (childNodes.hasNext()) {
            final DataSchemaNode child = childNodes.next();
            LyvNodeData lyvNodeData = new LyvNodeData(this.schemaContext, child, keys);
            ConsoleLine consoleLine = new ConsoleLine(new ArrayList<>(isConnected), lyvNodeData, inputOutput,
                    removeChoiceQnames, namespacePrefix);
            lines.add(consoleLine);
            List<QName> keyDefinitions = Collections.emptyList();
            if (child instanceof ListSchemaNode) {
                keyDefinitions = ((ListSchemaNode) child).getKeyDefinition();
            }
            resolveChildNodes(lines, isConnected, child, childNodes.hasNext() || actionExists, inputOutput,
                    removeChoiceQnames, keyDefinitions);
            this.treeDepth++;
        }
    }

    @SuppressFBWarnings(value = "SLF4J_SIGN_ONLY_FORMAT",
                        justification = "Valid output from LYV is dependent on Logback output")
    private void printLines(final List<Line> lines) {
        for (Line l : lines) {
            final String linesText = l.toString();
            LOG.info("{}", linesText.substring(0, min(linesText.length(), lineLength)));
        }
    }

    private void printHelp() {
        LOG.info(
                "tree - tree is printed in following format <status>--<flags> <name><opts> <type> <if-features>\n"
                        + "\n"
                        + " <status> is one of:\n"
                        + "\n"
                        + "    +  for current\n"
                        + "    x  for deprecated\n"
                        + "    o  for obsolete\n"
                        + "\n"
                        + " <flags> is one of:\n"
                        + "\n"
                        + "    rw  for configuration data\n"
                        + "    ro  for non-configuration data, output parameters to rpcs\n"
                        + "       and actions, and notification parameters\n"
                        + "    -w  for input parameters to rpcs and actions\n"
                        + "    -x  for rpcs and actions\n"
                        + "    -n  for notifications\n"
                        + "\n"
                        + " <name> is the name of the node:\n"
                        + "\n"
                        + "    (<name>) means that the node is a choice node\n"
                        + "    :(<name>) means that the node is a case node\n"
                        + "\n"
                        + " <opts> is one of:\n"
                        + "\n"
                        + "    ?  for an optional leaf, choice\n"
                        + "    *  for a leaf-list or list\n"
                        + "    [<keys>] for a list's keys\n"
                        + "\n"
                        + " <type> is the name of the type for leafs and leaf-lists.\n"
                        + "  If the type is a leafref, the type is printed as \"-> TARGET\",\n"
                        + "  whereTARGET is the leafref path, with prefixes removed if possible.\n"
                        + "\n"
                        + " <if-features> is the list of features this node depends on, printed\n"
                        + "     within curly brackets and a question mark \"{...}?\"\n");
    }

    @Override
    public Help getHelp() {
        return new Help(HELP_NAME, HELP_DESCRIPTION);
    }

    @Override
    public Optional<GroupArguments> getGroupArguments() {
        final GroupArguments groupArguments = new GroupArguments(HELP_NAME,
                "Tree format based arguments: ");
        groupArguments.addOption("Number of children to print (0 = all the child nodes).",
                Collections.singletonList("--tree-depth"), false, "?", 0,
                new CollectionArgumentChoice<>(Collections.emptyList()), Integer.TYPE);
        groupArguments.addOption("Number of characters to print for each line (print the whole line).",
                Collections.singletonList("--tree-line-length"), false, "?", 0,
                new CollectionArgumentChoice<>(Collections.emptyList()), Integer.TYPE);
        groupArguments.addOption("Print help information for symbols used in tree format.",
                Collections.singletonList("--tree-help"), true, null, null,
                new CollectionArgumentChoice<>(Collections.emptyList()), Boolean.TYPE);
        groupArguments.addOption("Use the whole module name instead of prefix.",
                Collections.singletonList("--tree-prefix-module"), true, null, null,
                new CollectionArgumentChoice<>(Collections.emptyList()), Boolean.TYPE);
        groupArguments.addOption("Use prefix with used module.",
                Collections.singletonList("--tree-prefix-main-module"), true, null, null,
                new CollectionArgumentChoice<>(Collections.emptyList()), Boolean.TYPE);
        return Optional.of(groupArguments);
    }
}

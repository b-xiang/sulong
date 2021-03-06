/*
 * Copyright (c) 2017, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.runtime.debug.scope;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Scope;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.llvm.runtime.LLVMContext;
import com.oracle.truffle.llvm.runtime.debug.LLVMDebugValue;
import com.oracle.truffle.llvm.runtime.debug.LLVMDebugObject;
import com.oracle.truffle.llvm.runtime.debug.LLVMSourceContext;
import com.oracle.truffle.llvm.runtime.debug.LLVMSourceSymbol;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMNode;

import java.util.ArrayList;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class LLVMSourceScope {

    @TruffleBoundary
    public static Iterable<Scope> create(Node node, Frame frame, LLVMContext context) {
        final LLVMSourceContext sourceContext = context.getSourceContext();
        final RootNode rootNode = node.getRootNode();

        if (rootNode == null || !(node instanceof LLVMNode)) {
            return Collections.singleton(new LLVMSourceScope(sourceContext, node).toScope(frame));
        }

        LLVMSourceLocation scope = ((LLVMNode) node).getSourceLocation();
        if (scope == null) {
            return Collections.singleton(new LLVMSourceScope(sourceContext, rootNode).toScope(frame));
        }
        final SourceSection sourceSection = node.getSourceSection();

        LLVMSourceScope baseScope = new LLVMSourceScope(sourceContext, new LinkedList<>(), rootNode);
        LLVMSourceScope staticScope = null;

        for (boolean isLocalScope = true; isLocalScope && scope != null; scope = scope.getParent()) {
            final LLVMSourceScope next = toScope(scope, sourceContext, rootNode, sourceSection);
            copySymbols(next, baseScope);
            if (scope.getKind() == LLVMSourceLocation.Kind.FUNCTION) {
                baseScope.setName(next.getName());
                if (scope.getCompileUnit() != null) {
                    staticScope = toScope(scope.getCompileUnit(), sourceContext, null, sourceSection);
                }
                isLocalScope = false;
            }
        }

        List<Scope> scopeList = new ArrayList<>();
        scopeList.add(baseScope.toScope(frame));
        for (; scope != null; scope = scope.getParent()) {
            // e.g. lambdas are compiled to calls to a method in a locally defined class. We
            // cannot access the locals of the enclosing function since they do not lie on the
            // function's frame. They are still accessible from the calling function's frame, so
            // we can simply ignore this scope here. Also, any variables actually used in the
            // lambda would still be available as the members of the 'this' pointer.
            final LLVMSourceScope next = toScope(scope, sourceContext, null, sourceSection);
            switch (scope.getKind()) {
                case NAMESPACE:
                case FILE:
                case BLOCK:
                    if (next.hasSymbols()) {
                        scopeList.add(next.toScope(frame));
                    }
                    break;

                case COMPILEUNIT:
                    if (staticScope == null) {
                        staticScope = next;
                    } else {
                        copySymbols(next, staticScope);
                    }
                    break;
            }
        }

        if (staticScope != null && staticScope.hasSymbols()) {
            scopeList.add(staticScope.toScope(frame));
        }

        return Collections.unmodifiableList(scopeList);
    }

    private static void copySymbols(LLVMSourceScope source, LLVMSourceScope target) {
        // always exclude shadowed symbols
        if (!source.symbols.isEmpty()) {
            final Set<String> names = target.symbols.stream().map(LLVMSourceSymbol::getName).collect(Collectors.toSet());
            source.symbols.stream().filter(s -> !names.contains(s.getName())).forEach(target.symbols::add);
        }
    }

    private static LLVMSourceScope toScope(LLVMSourceLocation scope, LLVMSourceContext context, Node node, SourceSection sourceSection) {
        if (!scope.hasSymbols()) {
            final LLVMSourceScope sourceScope = new LLVMSourceScope(context, node);
            sourceScope.setName(scope.getName());
            return sourceScope;
        }

        final List<LLVMSourceSymbol> symbols = new LinkedList<>();
        final LLVMSourceScope sourceScope = new LLVMSourceScope(context, symbols, node);
        sourceScope.setName(scope.getName());

        for (LLVMSourceSymbol symbol : scope.getSymbols()) {
            if (symbol.isGlobal() || isDeclaredBefore(symbol, sourceSection)) {
                symbols.add(symbol);
            }
        }

        return sourceScope;
    }

    private static boolean isDeclaredBefore(LLVMSourceSymbol symbol, SourceSection useLoc) {
        // we want to hide any locals that we definitely know are not in scope, we should display
        // any for which we can't tell
        if (useLoc == null) {
            return true;
        }

        LLVMSourceLocation symbolDecl = symbol.getLocation();
        if (symbolDecl == null) {
            return true;
        }

        SourceSection declLoc = symbolDecl.getSourceSection();
        if (declLoc == null) {
            return true;
        }

        if (declLoc.getSource().equals(useLoc.getSource())) {
            return declLoc.getCharIndex() <= useLoc.getCharIndex();
        }

        return true;
    }

    private static final String DEFAULT_NAME = "<scope>";

    private final LLVMSourceContext context;
    private final List<LLVMSourceSymbol> symbols;
    private final Node node;

    private String name;

    private LLVMSourceScope(LLVMSourceContext context, Node node) {
        this(context, Collections.emptyList(), node);
    }

    private LLVMSourceScope(LLVMSourceContext context, List<LLVMSourceSymbol> symbols, Node node) {
        this.context = context;
        this.symbols = symbols;
        this.node = node;
        this.name = DEFAULT_NAME;
    }

    private void setName(String name) {
        this.name = name;
    }

    private boolean hasSymbols() {
        return !symbols.isEmpty();
    }

    protected String getName() {
        return name;
    }

    @TruffleBoundary
    protected Object getVariables(Frame frame) {
        final Map<String, LLVMDebugObject> vars = new HashMap<>();

        if (frame != null && !symbols.isEmpty()) {
            for (FrameSlot slot : frame.getFrameDescriptor().getSlots()) {
                if (slot.getIdentifier() instanceof LLVMSourceSymbol && frame.getValue(slot) instanceof LLVMDebugValue) {
                    final LLVMSourceSymbol symbol = (LLVMSourceSymbol) slot.getIdentifier();
                    final LLVMDebugObject value = ((LLVMDebugValue) frame.getValue(slot)).getValue(symbol);
                    if (symbols.contains(symbol)) {
                        vars.put(symbol.getName(), value);
                    }
                }
            }
        }

        for (LLVMSourceSymbol symbol : symbols) {
            if (!vars.containsKey(symbol.getName())) {
                LLVMDebugValue dbgVal = context.getStatic(symbol);

                if (dbgVal == null) {
                    final LLVMFrameValueAccess allocation = context.getFrameValue(symbol);
                    if (allocation != null && frame != null) {
                        dbgVal = allocation.getValue(frame);
                    }
                }

                if (dbgVal == null) {
                    dbgVal = LLVMDebugValue.UNAVAILABLE;
                }

                vars.put(symbol.getName(), dbgVal.getValue(symbol));
            }
        }

        return new LLVMSourceScopeVariables(vars);
    }

    private Scope toScope(Frame frame) {
        return Scope.newBuilder(name, getVariables(frame)).node(node).build();
    }
}

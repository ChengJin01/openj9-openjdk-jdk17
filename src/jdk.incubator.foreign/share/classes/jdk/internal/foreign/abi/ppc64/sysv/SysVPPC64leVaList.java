/*
 *  Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.  Oracle designates this
 *  particular file as subject to the "Classpath" exception as provided
 *  by Oracle in the LICENSE file that accompanied this code.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */

/*
 * ===========================================================================
 * (c) Copyright IBM Corp. 2021, 2021 All Rights Reserved
 * ===========================================================================
 */

package jdk.internal.foreign.abi.ppc64.sysv;

import jdk.incubator.foreign.*;
import jdk.incubator.foreign.CLinker.VaList;
import jdk.internal.foreign.ResourceScopeImpl;
import jdk.internal.foreign.abi.SharedUtils;
import jdk.internal.foreign.abi.SharedUtils.SimpleVaArg;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static jdk.internal.foreign.PlatformLayouts.SysVPPC64le.C_POINTER;

/**
 * This file is copied from x86/windows (Windows/x86_64) as a placeholder for compilation
 * as VaList on Linux/ppc64le at Java level is not yet implemented for the moment.
 * The defintion VaList must map to the underlying struct of va_list defined on Linux/ppc64le
 * which might be similar to Windows/x86_64. Thus, futher analysis on the struct is required
 * to understand how the struct is laid out in memory (e.g. the type & size of each field in
 * va_list) and how the registers are allocated for va_list.
 */
public non-sealed class SysVPPC64leVaList implements VaList {
    public static final Class<?> CARRIER = MemoryAddress.class;
    private static final long VA_SLOT_SIZE_BYTES = 8;
    private static final VarHandle VH_address = MemoryHandles.asAddressVarHandle(C_POINTER.varHandle(long.class));

    private static final VaList EMPTY = new SharedUtils.EmptyVaList(MemoryAddress.NULL);

    private MemorySegment segment;
    private final ResourceScope scope;

    private SysVPPC64leVaList(MemorySegment segment, ResourceScope scope) {
        this.segment = segment;
        this.scope = scope;
    }

    public static final VaList empty() {
        return EMPTY;
    }

    @Override
    public int vargAsInt(MemoryLayout layout) {
        throw new InternalError("vargAsInt is not yet implemented"); //$NON-NLS-1$
    }

    @Override
    public long vargAsLong(MemoryLayout layout) {
        throw new InternalError("vargAsLong is not yet implemented"); //$NON-NLS-1$
    }

    @Override
    public double vargAsDouble(MemoryLayout layout) {
        throw new InternalError("vargAsDouble is not yet implemented"); //$NON-NLS-1$
    }

    @Override
    public MemoryAddress vargAsAddress(MemoryLayout layout) {
        throw new InternalError("vargAsAddress is not yet implemented"); //$NON-NLS-1$
    }

    @Override
    public MemorySegment vargAsSegment(MemoryLayout layout, SegmentAllocator allocator) {
        throw new InternalError("vargAsSegment is not yet implemented"); //$NON-NLS-1$
    }

    @Override
    public MemorySegment vargAsSegment(MemoryLayout layout, ResourceScope scope) {
        throw new InternalError("vargAsSegment is not yet implemented"); //$NON-NLS-1$
    }

    private Object read(Class<?> carrier, MemoryLayout layout) {
        return read(carrier, layout, SharedUtils.THROWING_ALLOCATOR);
    }

    private Object read(Class<?> carrier, MemoryLayout layout, SegmentAllocator allocator) {
        Objects.requireNonNull(layout);
        SharedUtils.checkCompatibleType(carrier, layout, SysVPPC64leLinker.ADDRESS_SIZE);
        Object res;
        if (carrier == MemorySegment.class) {
            TypeClass typeClass = TypeClass.typeClassFor(layout);
            res = switch (typeClass) {
                case STRUCT_REFERENCE -> {
                    MemoryAddress structAddr = (MemoryAddress) VH_address.get(segment);
                    MemorySegment struct = structAddr.asSegment(layout.byteSize(), scope());
                    MemorySegment seg = allocator.allocate(layout);
                    seg.copyFrom(struct);
                    yield seg;
                }
                case STRUCT_REGISTER -> {
                    MemorySegment struct = allocator.allocate(layout);
                    struct.copyFrom(segment.asSlice(0L, layout.byteSize()));
                    yield struct;
                }
                default -> throw new IllegalStateException("Unexpected TypeClass: " + typeClass);
            };
        } else {
            VarHandle reader = SharedUtils.vhPrimitiveOrAddress(carrier, layout);
            res = reader.get(segment);
        }
        segment = segment.asSlice(VA_SLOT_SIZE_BYTES);
        return res;
    }

    @Override
    public void skip(MemoryLayout... layouts) {
        throw new InternalError("skip is not yet implemented"); //$NON-NLS-1$
    }

    static SysVPPC64leVaList ofAddress(MemoryAddress addr, ResourceScope scope) {
        throw new InternalError("ofAddress is not yet implemented"); //$NON-NLS-1$
    }

    static Builder builder(ResourceScope scope) {
        return new Builder(scope);
    }

    @Override
    public ResourceScope scope() {
        return scope;
    }

    @Override
    public VaList copy() {
        ((ResourceScopeImpl)scope).checkValidStateSlow();
        return new SysVPPC64leVaList(segment, scope);
    }

    @Override
    public MemoryAddress address() {
        return segment.address();
    }

    public static non-sealed class Builder implements VaList.Builder {

        private final ResourceScope scope;
        private final List<SimpleVaArg> args = new ArrayList<>();

        public Builder(ResourceScope scope) {
            ((ResourceScopeImpl)scope).checkValidStateSlow();
            this.scope = scope;
        }

        private Builder arg(Class<?> carrier, MemoryLayout layout, Object value) {
            Objects.requireNonNull(layout);
            Objects.requireNonNull(value);
            SharedUtils.checkCompatibleType(carrier, layout, SysVPPC64leLinker.ADDRESS_SIZE);
            args.add(new SimpleVaArg(carrier, layout, value));
            return this;
        }

        @Override
        public Builder vargFromInt(ValueLayout layout, int value) {
            throw new InternalError("vargFromInt is not yet implemented"); //$NON-NLS-1$
        }

        @Override
        public Builder vargFromLong(ValueLayout layout, long value) {
            throw new InternalError("vargFromLong is not yet implemented"); //$NON-NLS-1$
        }

        @Override
        public Builder vargFromDouble(ValueLayout layout, double value) {
            throw new InternalError("vargFromDouble is not yet implemented"); //$NON-NLS-1$
        }

        @Override
        public Builder vargFromAddress(ValueLayout layout, Addressable value) {
            throw new InternalError("vargFromAddress is not yet implemented"); //$NON-NLS-1$
        }

        @Override
        public Builder vargFromSegment(GroupLayout layout, MemorySegment value) {
            throw new InternalError("vargFromSegment is not yet implemented"); //$NON-NLS-1$
        }

        public VaList build() {
            if (args.isEmpty()) {
                return EMPTY;
            }
            SegmentAllocator allocator = SegmentAllocator.arenaAllocator(scope);
            MemorySegment segment = allocator.allocate(VA_SLOT_SIZE_BYTES * args.size());
            List<MemorySegment> attachedSegments = new ArrayList<>();
            attachedSegments.add(segment);
            MemorySegment cursor = segment;

            for (SimpleVaArg arg : args) {
                if (arg.carrier == MemorySegment.class) {
                    MemorySegment msArg = ((MemorySegment) arg.value);
                    TypeClass typeClass = TypeClass.typeClassFor(arg.layout);
                    switch (typeClass) {
                        case STRUCT_REFERENCE -> {
                            MemorySegment copy = allocator.allocate(arg.layout);
                            copy.copyFrom(msArg); // by-value
                            attachedSegments.add(copy);
                            VH_address.set(cursor, copy.address());
                        }
                        case STRUCT_REGISTER -> {
                            MemorySegment slice = cursor.asSlice(0, VA_SLOT_SIZE_BYTES);
                            slice.copyFrom(msArg);
                        }
                        default -> throw new IllegalStateException("Unexpected TypeClass: " + typeClass);
                    }
                } else {
                    VarHandle writer = arg.varHandle();
                    writer.set(cursor, arg.value);
                }
                cursor = cursor.asSlice(VA_SLOT_SIZE_BYTES);
            }

            return new SysVPPC64leVaList(segment, scope);
        }
    }
}

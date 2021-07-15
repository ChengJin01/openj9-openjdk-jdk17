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

package jdk.internal.foreign.abi.s390x.sysv;

import jdk.incubator.foreign.*;
import jdk.internal.foreign.Utils;
import jdk.internal.foreign.abi.SharedUtils;
import jdk.internal.misc.Unsafe;

import java.lang.invoke.VarHandle;
import java.lang.ref.Cleaner;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static jdk.internal.foreign.PlatformLayouts.SysVS390x;
import static jdk.incubator.foreign.CLinker.VaList;
import static jdk.incubator.foreign.MemoryLayout.PathElement.groupElement;
import static jdk.internal.foreign.abi.SharedUtils.SimpleVaArg;
import static jdk.internal.foreign.abi.SharedUtils.THROWING_ALLOCATOR;
import static jdk.internal.foreign.abi.SharedUtils.checkCompatibleType;
import static jdk.internal.foreign.abi.SharedUtils.vhPrimitiveOrAddress;

/**
 * This file is copied from x86/sysv (Linux/x86_64) as a placeholder for compilation
 * as VaList on Linux/s390x at Java level is not yet implemented for the moment.
 * The defintion VaList must map to the underlying struct of va_list defined on
 * Linux/s390x which might be similar to Linux/x86_64. Thus, futher analysis on
 * the struct is required to understand how the struct is laid out in memory (e.g.
 * the type & size of each field in va_list) and how the registers are allocated
 * for va_list according to the description in the publisized ABI document
 * at https://refspecs.linuxfoundation.org/ELF/zSeries/lzsabi0_zSeries.pdf.
 */
public non-sealed class SysVS390xVaList implements VaList {
    private static final Unsafe U = Unsafe.getUnsafe();

    static final Class<?> CARRIER = MemoryAddress.class;

    static final GroupLayout LAYOUT = MemoryLayout.structLayout(
        SysVS390x.C_INT.withName("gp_offset"),
        SysVS390x.C_INT.withName("fp_offset"),
        SysVS390x.C_POINTER.withName("overflow_arg_area"),
        SysVS390x.C_POINTER.withName("reg_save_area")
    ).withName("__va_list_tag");

    private static final MemoryLayout GP_REG = MemoryLayout.valueLayout(64, ByteOrder.nativeOrder());
    private static final MemoryLayout FP_REG = MemoryLayout.valueLayout(128, ByteOrder.nativeOrder());

    private static final GroupLayout LAYOUT_REG_SAVE_AREA = MemoryLayout.structLayout(
        GP_REG.withName("%rdi"),
        GP_REG.withName("%rsi"),
        GP_REG.withName("%rdx"),
        GP_REG.withName("%rcx"),
        GP_REG.withName("%r8"),
        GP_REG.withName("%r9"),
        FP_REG.withName("%xmm0"),
        FP_REG.withName("%xmm1"),
        FP_REG.withName("%xmm2"),
        FP_REG.withName("%xmm3"),
        FP_REG.withName("%xmm4"),
        FP_REG.withName("%xmm5"),
        FP_REG.withName("%xmm6"),
        FP_REG.withName("%xmm7")
    );

    private static final long FP_OFFSET = LAYOUT_REG_SAVE_AREA.byteOffset(groupElement("%xmm0"));

    private static final int GP_SLOT_SIZE = (int) GP_REG.byteSize();
    private static final int FP_SLOT_SIZE = (int) FP_REG.byteSize();

    private static final int MAX_GP_OFFSET = (int) FP_OFFSET; // 6 regs used
    private static final int MAX_FP_OFFSET = (int) LAYOUT_REG_SAVE_AREA.byteSize(); // 8 16 byte regs

    private static final VarHandle VH_fp_offset = LAYOUT.varHandle(int.class, groupElement("fp_offset"));
    private static final VarHandle VH_gp_offset = LAYOUT.varHandle(int.class, groupElement("gp_offset"));
    private static final VarHandle VH_overflow_arg_area
        = MemoryHandles.asAddressVarHandle(LAYOUT.varHandle(long.class, groupElement("overflow_arg_area")));
    private static final VarHandle VH_reg_save_area
        = MemoryHandles.asAddressVarHandle(LAYOUT.varHandle(long.class, groupElement("reg_save_area")));

    private static final Cleaner cleaner = Cleaner.create();
    private static final VaList EMPTY = new SharedUtils.EmptyVaList(emptyListAddress());

    private final MemorySegment segment;
    private final MemorySegment regSaveArea;

    private SysVS390xVaList(MemorySegment segment, MemorySegment regSaveArea) {
        this.segment = segment;
        this.regSaveArea = regSaveArea;
    }

    private static SysVS390xVaList readFromSegment(MemorySegment segment) {
        MemorySegment regSaveArea = getRegSaveArea(segment);
        return new SysVS390xVaList(segment, regSaveArea);
    }

    private static MemoryAddress emptyListAddress() {
        long ptr = U.allocateMemory(LAYOUT.byteSize());
        MemorySegment base = MemoryAddress.ofLong(ptr).asSegment(
                LAYOUT.byteSize(), () -> U.freeMemory(ptr), ResourceScope.newSharedScope());
        cleaner.register(SysVS390xVaList.class, () -> base.scope().close());
        VH_gp_offset.set(base, MAX_GP_OFFSET);
        VH_fp_offset.set(base, MAX_FP_OFFSET);
        VH_overflow_arg_area.set(base, MemoryAddress.NULL);
        VH_reg_save_area.set(base, MemoryAddress.NULL);
        return base.address();
    }

    public static VaList empty() {
        return EMPTY;
    }

    private int currentGPOffset() {
        return (int) VH_gp_offset.get(segment);
    }

    private void currentGPOffset(int i) {
        VH_gp_offset.set(segment, i);
    }

    private int currentFPOffset() {
        return (int) VH_fp_offset.get(segment);
    }

    private void currentFPOffset(int i) {
        VH_fp_offset.set(segment, i);
    }

    private MemoryAddress stackPtr() {
        return (MemoryAddress) VH_overflow_arg_area.get(segment);
    }

    private void stackPtr(MemoryAddress ptr) {
        VH_overflow_arg_area.set(segment, ptr);
    }

    private MemorySegment regSaveArea() {
        return getRegSaveArea(segment);
    }

    private static MemorySegment getRegSaveArea(MemorySegment segment) {
        return ((MemoryAddress)VH_reg_save_area.get(segment)).asSegment(
                LAYOUT_REG_SAVE_AREA.byteSize(), segment.scope());
    }

    private void preAlignStack(MemoryLayout layout) {
        if (layout.byteAlignment() > 8) {
            stackPtr(Utils.alignUp(stackPtr(), 16));
        }
    }

    private void postAlignStack(MemoryLayout layout) {
        stackPtr(Utils.alignUp(stackPtr().addOffset(layout.byteSize()), 8));
    }

    @Override
    public int vargAsInt(MemoryLayout layout) {
        throw new InternalError("vargAsInt is not yet implemented");	//$NON-NLS-1$
    }

    @Override
    public long vargAsLong(MemoryLayout layout) {
        throw new InternalError("vargAsLong is not yet implemented");	//$NON-NLS-1$
    }

    @Override
    public double vargAsDouble(MemoryLayout layout) {
        throw new InternalError("vargAsDouble is not yet implemented");	//$NON-NLS-1$
    }

    @Override
    public MemoryAddress vargAsAddress(MemoryLayout layout) {
        throw new InternalError("vargAsAddress is not yet implemented");	//$NON-NLS-1$
    }

    @Override
    public MemorySegment vargAsSegment(MemoryLayout layout, SegmentAllocator allocator) {
        throw new InternalError("vargAsSegment is not yet implemented");	//$NON-NLS-1$
    }

    @Override
    public MemorySegment vargAsSegment(MemoryLayout layout, ResourceScope scope) {
        throw new InternalError("vargAsSegment is not yet implemented");	//$NON-NLS-1$
    }

    private Object read(Class<?> carrier, MemoryLayout layout) {
        return read(carrier, layout, THROWING_ALLOCATOR);
    }

    private Object read(Class<?> carrier, MemoryLayout layout, SegmentAllocator allocator) {
        Objects.requireNonNull(layout);
        checkCompatibleType(carrier, layout, SysVS390xLinker.ADDRESS_SIZE);
        TypeClass typeClass = TypeClass.classifyLayout(layout);
        if (isRegOverflow(currentGPOffset(), currentFPOffset(), typeClass)
                || typeClass.inMemory()) {
            preAlignStack(layout);
            return switch (typeClass.kind()) {
                case STRUCT -> {
                    MemorySegment slice = stackPtr().asSegment(layout.byteSize(), scope());
                    MemorySegment seg = allocator.allocate(layout);
                    seg.copyFrom(slice);
                    postAlignStack(layout);
                    yield seg;
                }
                case POINTER, INTEGER, FLOAT -> {
                    VarHandle reader = vhPrimitiveOrAddress(carrier, layout);
                    try (ResourceScope localScope = ResourceScope.newConfinedScope()) {
                        MemorySegment slice = stackPtr().asSegment(layout.byteSize(), localScope);
                        Object res = reader.get(slice);
                        postAlignStack(layout);
                        yield res;
                    }
                }
            };
        } else {
            return switch (typeClass.kind()) {
                case STRUCT -> {
                    MemorySegment value = allocator.allocate(layout);
                    int classIdx = 0;
                    long offset = 0;
                    while (offset < layout.byteSize()) {
                        final long copy = Math.min(layout.byteSize() - offset, 8);
                        boolean isSSE = typeClass.classes.get(classIdx++) == ArgumentClassImpl.SSE;
                        MemorySegment slice = value.asSlice(offset, copy);
                        if (isSSE) {
                            slice.copyFrom(regSaveArea.asSlice(currentFPOffset(), copy));
                            currentFPOffset(currentFPOffset() + FP_SLOT_SIZE);
                        } else {
                            slice.copyFrom(regSaveArea.asSlice(currentGPOffset(), copy));
                            currentGPOffset(currentGPOffset() + GP_SLOT_SIZE);
                        }
                        offset += copy;
                    }
                    yield value;
                }
                case POINTER, INTEGER -> {
                    VarHandle reader = SharedUtils.vhPrimitiveOrAddress(carrier, layout);
                    Object res = reader.get(regSaveArea.asSlice(currentGPOffset()));
                    currentGPOffset(currentGPOffset() + GP_SLOT_SIZE);
                    yield res;
                }
                case FLOAT -> {
                    VarHandle reader = layout.varHandle(carrier);
                    Object res = reader.get(regSaveArea.asSlice(currentFPOffset()));
                    currentFPOffset(currentFPOffset() + FP_SLOT_SIZE);
                    yield res;
                }
            };
        }
    }

    @Override
    public void skip(MemoryLayout... layouts) {
        throw new InternalError("skip is not yet implemented");	//$NON-NLS-1$
    }

    static SysVS390xVaList.Builder builder(ResourceScope scope) {
        return new SysVS390xVaList.Builder(scope);
    }

    public static VaList ofAddress(MemoryAddress ma, ResourceScope scope) {
        throw new InternalError("ofAddress is not yet implemented");	//$NON-NLS-1$
    }

    @Override
    public ResourceScope scope() {
        return segment.scope();
    }

    @Override
    public VaList copy() {
        MemorySegment copy = MemorySegment.allocateNative(LAYOUT, segment.scope());
        copy.copyFrom(segment);
        return new SysVS390xVaList(copy, regSaveArea);
    }

    @Override
    public MemoryAddress address() {
        return segment.address();
    }

    private static boolean isRegOverflow(long currentGPOffset, long currentFPOffset, TypeClass typeClass) {
        return currentGPOffset > MAX_GP_OFFSET - typeClass.nIntegerRegs() * GP_SLOT_SIZE
                || currentFPOffset > MAX_FP_OFFSET - typeClass.nVectorRegs() * FP_SLOT_SIZE;
    }

    @Override
    public String toString() {
        return "SysVS390xVaList{"
               + "gp_offset=" + currentGPOffset()
               + ", fp_offset=" + currentFPOffset()
               + ", overflow_arg_area=" + stackPtr()
               + ", reg_save_area=" + regSaveArea()
               + '}';
    }

    public static non-sealed class Builder implements VaList.Builder {
        private final ResourceScope scope;
        private final MemorySegment reg_save_area;
        private long currentGPOffset = 0;
        private long currentFPOffset = FP_OFFSET;
        private final List<SimpleVaArg> stackArgs = new ArrayList<>();

        public Builder(ResourceScope scope) {
            this.scope = scope;
            this.reg_save_area = MemorySegment.allocateNative(LAYOUT_REG_SAVE_AREA, scope);
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

        private Builder arg(Class<?> carrier, MemoryLayout layout, Object value) {
            Objects.requireNonNull(layout);
            Objects.requireNonNull(value);
            checkCompatibleType(carrier, layout, SysVS390xLinker.ADDRESS_SIZE);
            TypeClass typeClass = TypeClass.classifyLayout(layout);
            if (isRegOverflow(currentGPOffset, currentFPOffset, typeClass)
                    || typeClass.inMemory()) {
                // stack it!
                stackArgs.add(new SimpleVaArg(carrier, layout, value));
            } else {
                switch (typeClass.kind()) {
                    case STRUCT -> {
                        MemorySegment valueSegment = (MemorySegment) value;
                        int classIdx = 0;
                        long offset = 0;
                        while (offset < layout.byteSize()) {
                            final long copy = Math.min(layout.byteSize() - offset, 8);
                            boolean isSSE = typeClass.classes.get(classIdx++) == ArgumentClassImpl.SSE;
                            MemorySegment slice = valueSegment.asSlice(offset, copy);
                            if (isSSE) {
                                reg_save_area.asSlice(currentFPOffset, copy).copyFrom(slice);
                                currentFPOffset += FP_SLOT_SIZE;
                            } else {
                                reg_save_area.asSlice(currentGPOffset, copy).copyFrom(slice);
                                currentGPOffset += GP_SLOT_SIZE;
                            }
                            offset += copy;
                        }
                    }
                    case POINTER, INTEGER -> {
                        VarHandle writer = SharedUtils.vhPrimitiveOrAddress(carrier, layout);
                        writer.set(reg_save_area.asSlice(currentGPOffset), value);
                        currentGPOffset += GP_SLOT_SIZE;
                    }
                    case FLOAT -> {
                        VarHandle writer = layout.varHandle(carrier);
                        writer.set(reg_save_area.asSlice(currentFPOffset), value);
                        currentFPOffset += FP_SLOT_SIZE;
                    }
                }
            }
            return this;
        }

        private boolean isEmpty() {
            return currentGPOffset == 0 && currentFPOffset == FP_OFFSET && stackArgs.isEmpty();
        }

        public VaList build() {
            if (isEmpty()) {
                return EMPTY;
            }

            SegmentAllocator allocator = SegmentAllocator.arenaAllocator(scope);
            MemorySegment vaListSegment = allocator.allocate(LAYOUT);
            MemoryAddress stackArgsPtr = MemoryAddress.NULL;
            if (!stackArgs.isEmpty()) {
                long stackArgsSize = stackArgs.stream().reduce(0L, (acc, e) -> acc + e.layout.byteSize(), Long::sum);
                MemorySegment stackArgsSegment = allocator.allocate(stackArgsSize, 16);
                MemorySegment maOverflowArgArea = stackArgsSegment;
                for (SimpleVaArg arg : stackArgs) {
                    if (arg.layout.byteSize() > 8) {
                        maOverflowArgArea = Utils.alignUp(maOverflowArgArea, Math.min(16, arg.layout.byteSize()));
                    }
                    if (arg.value instanceof MemorySegment) {
                        maOverflowArgArea.copyFrom((MemorySegment) arg.value);
                    } else {
                        VarHandle writer = arg.varHandle();
                        writer.set(maOverflowArgArea, arg.value);
                    }
                    maOverflowArgArea = maOverflowArgArea.asSlice(arg.layout.byteSize());
                }
                stackArgsPtr = stackArgsSegment.address();
            }

            VH_fp_offset.set(vaListSegment, (int) FP_OFFSET);
            VH_overflow_arg_area.set(vaListSegment, stackArgsPtr);
            VH_reg_save_area.set(vaListSegment, reg_save_area.address());
            assert reg_save_area.scope().ownerThread() == vaListSegment.scope().ownerThread();
            return new SysVS390xVaList(vaListSegment, reg_save_area);
        }
    }
}

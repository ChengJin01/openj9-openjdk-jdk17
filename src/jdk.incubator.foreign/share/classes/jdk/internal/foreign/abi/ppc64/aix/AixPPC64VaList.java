/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * ===========================================================================
 * (c) Copyright IBM Corp. 2021, 2022 All Rights Reserved
 * ===========================================================================
 */

package jdk.internal.foreign.abi.ppc64.aix;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import jdk.incubator.foreign.*;
import jdk.incubator.foreign.CLinker.VaList;
import jdk.internal.foreign.abi.ppc64.TypeClass;
import jdk.internal.foreign.abi.SharedUtils;
import jdk.internal.foreign.abi.SharedUtils.SimpleVaArg;
import jdk.internal.foreign.ResourceScopeImpl;
import static jdk.internal.foreign.PlatformLayouts.AIX;

/**
 * This class implements VaList specific to AIX/ppc64 based on the publisized ABI document at
 * https://www.ibm.com/docs/en/ssw_aix_72/pdf/assembler_pdf.pdf against the code of VaList on
 * x64/windows as the template.
 *
 * va_arg impl on AIX/ppc64:
 * typedef void * va_list;
 *
 * Specifically, va_list is simply a pointer (similar to the va_list on x64/windows) to a buffer
 * with all supportted types of arugments, including struct (passed by value), pointer and 
 * primitive types, which are aligned with 8 bytes.
 */
public non-sealed class AixPPC64VaList implements VaList {
	public static final Class<?> CARRIER = MemoryAddress.class;

	/* Every primitive/pointer occupies 8 bytes and structs are aligned
	 * with 8 bytes in the total size when stacking the va_list buffer. 
	 */
	private static final long VA_LIST_SLOT_BYTES = 8;
	private static final VaList EMPTY = new SharedUtils.EmptyVaList(MemoryAddress.NULL);

	private MemorySegment segment;
	private final ResourceScope scope;

	private AixPPC64VaList(MemorySegment segment, ResourceScope scope) {
		this.segment = segment;
		this.scope = scope;
	}

	public static final VaList empty() {
		return EMPTY;
	}

	@Override
	public int vargAsInt(MemoryLayout layout) {
		return Math.toIntExact((long)readArg(layout));
	}

	@Override
	public long vargAsLong(MemoryLayout layout) {
		return (long)readArg(layout);
	}

	@Override
	public double vargAsDouble(MemoryLayout layout) {
		return (double)readArg(layout);
	}

	@Override
	public MemoryAddress vargAsAddress(MemoryLayout layout) {
		return (MemoryAddress)readArg(layout);
	}

	@Override
	public MemorySegment vargAsSegment(MemoryLayout layout, SegmentAllocator allocator) {
		Objects.requireNonNull(allocator);
		return (MemorySegment)readArg(layout, allocator);
	}

	@Override
	public MemorySegment vargAsSegment(MemoryLayout layout, ResourceScope scope) {
		return vargAsSegment(layout, SegmentAllocator.ofScope(scope));
	}

	private static long getAlignedArgSize(MemoryLayout argLayout) {
		long argLayoutSize = VA_LIST_SLOT_BYTES; // Always aligned with 8 bytes for primitives/pointer by default

		/* As with primitives, a struct should aligned with 8 bytes */
		if (argLayout instanceof GroupLayout) {
			argLayoutSize = argLayout.byteSize();
			if ((argLayoutSize % VA_LIST_SLOT_BYTES) > 0) {
				argLayoutSize = (argLayoutSize / VA_LIST_SLOT_BYTES) * VA_LIST_SLOT_BYTES + VA_LIST_SLOT_BYTES;
			}
		}

		return argLayoutSize;
	}

	private Object readArg(MemoryLayout argLayout) {
		return readArg(argLayout, SharedUtils.THROWING_ALLOCATOR);
	}

	private Object readArg(MemoryLayout argLayout, SegmentAllocator allocator) {
		Objects.requireNonNull(argLayout);
		long argByteSize = VA_LIST_SLOT_BYTES; // Always aligned with 8 bytes for primitives/pointer by default
		Object argument = null;

		TypeClass typeClass = TypeClass.classifyLayout(argLayout);
		switch (typeClass) {
			case BASE, POINTER -> {
				VarHandle argHandle = TypeClass.classifyVarHandle((ValueLayout)argLayout);
				argument = argHandle.get(segment);
			}
			case STRUCT -> {
				argByteSize = getAlignedArgSize(argLayout);
				/* Copy the struct argument with the aligned size from the va_list buffer to allocated memory */
				argument = allocator.allocate(argByteSize);
				((MemorySegment)argument).copyFrom(segment.asSlice(0, argByteSize));
			}
			default -> throw new IllegalStateException("Unsupported TypeClass: " + typeClass);
		}

		/* Move to the next argument in the va_list buffer */
		segment = segment.asSlice(argByteSize);
		return argument;
	}

	@Override
	public void skip(MemoryLayout... layouts) {
		Objects.requireNonNull(layouts);
		((ResourceScopeImpl)scope).checkValidStateSlow();
		Stream.of(layouts).forEach(Objects::requireNonNull);

		/* All primitves/pointer (aligned with 8 bytes) are directly stored
		 * in the va_list buffer and all elements of stuct are totally copied
		 * to the va_list buffer (instead of storing the va_list address), in
		 * which case we need to calculate the total byte size to be skipped
		 * in the va_list buffer.
		 */
		long totalArgsSize = Stream.of(layouts).reduce(0L,
				(accum, layout) -> accum + getAlignedArgSize(layout), Long::sum);
		segment = segment.asSlice(totalArgsSize);
	}

	public static VaList ofAddress(MemoryAddress addr, ResourceScope scope) {
		MemorySegment segment = addr.asSegment(Long.MAX_VALUE, scope);
		return new AixPPC64VaList(segment, scope);
	}

	@Override
	public ResourceScope scope() {
		return scope;
	}

	@Override
	public VaList copy() {
		((ResourceScopeImpl)scope).checkValidStateSlow();
		return new AixPPC64VaList(segment, scope);
	}

	@Override
	public MemoryAddress address() {
		return segment.address();
	}

	@Override
	public String toString() {
		return "AixPPC64VaList{" + segment.address() + '}';
	}

	static Builder builder(ResourceScope scope) {
		return new Builder(scope);
	}

	public static non-sealed class Builder implements VaList.Builder {
		private final ResourceScope scope;
		private final List<SimpleVaArg> stackArgs = new ArrayList<>();

		public Builder(ResourceScope scope) {
			((ResourceScopeImpl)scope).checkValidStateSlow();
			this.scope = scope;
		}

		private Builder setArg(MemoryLayout layout, Object value) {
			Objects.requireNonNull(layout);
			Objects.requireNonNull(value);
			Class<?> carrier = TypeClass.classifyCarrier(layout);
			SharedUtils.checkCompatibleType(carrier, layout, AixPPC64Linker.ADDRESS_SIZE);
			stackArgs.add(new SimpleVaArg(carrier, layout, value));
			return this;
		}

		@Override
		public Builder vargFromInt(ValueLayout layout, int value) {
			return setArg(layout, value);
		}

		@Override
		public Builder vargFromLong(ValueLayout layout, long value) {
			return setArg(layout, value);
		}

		@Override
		public Builder vargFromDouble(ValueLayout layout, double value) {
			return setArg(layout, value);
		}

		@Override
		public Builder vargFromAddress(ValueLayout layout, Addressable value) {
			return setArg(layout, value.address());
		}

		@Override
		public Builder vargFromSegment(GroupLayout layout, MemorySegment value) {
			return setArg(layout, value);
		}

		public VaList build() {
			if (stackArgs.isEmpty()) {
				return EMPTY;
			}

			/* All primitves/pointer (aligned with 8 bytes) are directly stored in the va_list buffer
			 * and all elements of stuct are totally copied to the va_list buffer (instead of storing
			 * the va_list address), in which  case we need to calculate the total byte size of the
			 * buffer to be allocated for va_list.
			 */
			long totalArgsSize = stackArgs.stream().reduce(0L,
					(accum, arg) -> accum + getAlignedArgSize(arg.layout), Long::sum);
			SegmentAllocator allocator = SegmentAllocator.arenaAllocator(scope);
			MemorySegment segment = allocator.allocate(totalArgsSize);
			MemorySegment cursorSegment = segment;

			for (SimpleVaArg arg : stackArgs) {
				MemoryLayout argLayout = arg.layout;
				TypeClass typeClass = TypeClass.classifyLayout(argLayout);

				switch (typeClass) {
					case BASE, POINTER -> {
						VarHandle argHandle = TypeClass.classifyVarHandle((ValueLayout)argLayout);
						argHandle.set(cursorSegment, arg.value);
						/* Move to the location for the next argument by 8 bytes */
						cursorSegment = cursorSegment.asSlice(VA_LIST_SLOT_BYTES);
					}
					case STRUCT -> {
						cursorSegment.copyFrom((MemorySegment)(arg.value));
						/* Move to the location for the next argument by the aligned struct size */
						cursorSegment = cursorSegment.asSlice(getAlignedArgSize(argLayout));
					}
					default -> throw new IllegalStateException("Unsupported TypeClass: " + typeClass);
				}
			}
			return new AixPPC64VaList(segment, scope);
		}
	}
}

/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.graphics.glutils;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/** Modified for the Vita: wraps a short[], no copy per draw. */
public class IndexArray implements IndexData {
    final ShortBuffer buffer;
    final ByteBuffer byteBuffer;
    private final boolean empty;

    public IndexArray(int maxIndices) {
        empty = maxIndices == 0;
        if (empty) {
            maxIndices = 1;
        }
        buffer = ShortBuffer.wrap(new short[maxIndices]);
        byteBuffer = null;
        buffer.flip();
    }

    @Override
    public int getNumIndices() {
        return empty ? 0 : buffer.limit();
    }

    @Override
    public int getNumMaxIndices() {
        return empty ? 0 : buffer.capacity();
    }

    @Override
    public void setIndices(short[] indices, int offset, int count) {
        buffer.clear();
        buffer.put(indices, offset, count);
        buffer.flip();
    }

    @Override
    public void setIndices(ShortBuffer indices) {
        int pos = indices.position();
        buffer.clear();
        buffer.limit(indices.remaining());
        buffer.put(indices);
        buffer.flip();
        indices.position(pos);
    }

    @Override
    public void updateIndices(int targetOffset, short[] indices, int offset, int count) {
        System.arraycopy(indices, offset, buffer.array(), buffer.arrayOffset() + targetOffset, count);
    }

    @Override
    public ShortBuffer getBuffer() {
        return buffer;
    }

    @Override
    public void bind() {
    }

    @Override
    public void unbind() {
    }

    @Override
    public void invalidate() {
    }

    @Override
    public void dispose() {
    }
}

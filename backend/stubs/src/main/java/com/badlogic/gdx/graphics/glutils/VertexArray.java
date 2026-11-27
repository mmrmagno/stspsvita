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

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.utils.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/** Modified for the Vita: attributes point into the backing array, no copy per draw. */
public class VertexArray implements VertexData {
    final VertexAttributes attributes;
    final FloatBuffer buffer;
    final ByteBuffer byteBuffer;
    boolean isBound = false;
    private ShaderProgram cachedShader;
    private int[] cachedLocations;

    public VertexArray(int numVertices, VertexAttribute... attributes) {
        this(numVertices, new VertexAttributes(attributes));
    }

    public VertexArray(int numVertices, VertexAttributes attributes) {
        this.attributes = attributes;
        byteBuffer = BufferUtils.newUnsafeByteBuffer(this.attributes.vertexSize * numVertices);
        buffer = byteBuffer.asFloatBuffer();
        buffer.flip();
        byteBuffer.flip();
    }

    @Override
    public void dispose() {
        BufferUtils.disposeUnsafeByteBuffer(byteBuffer);
    }

    @Override
    public FloatBuffer getBuffer() {
        return buffer;
    }

    @Override
    public int getNumVertices() {
        return buffer.limit() * 4 / attributes.vertexSize;
    }

    @Override
    public int getNumMaxVertices() {
        return byteBuffer.capacity() / attributes.vertexSize;
    }

    @Override
    public void setVertices(float[] vertices, int offset, int count) {
        BufferUtils.copy(vertices, byteBuffer, count, offset);
        buffer.position(0);
        buffer.limit(count);
    }

    @Override
    public void updateVertices(int targetOffset, float[] vertices, int sourceOffset, int count) {
        final int pos = byteBuffer.position();
        byteBuffer.position(targetOffset * 4);
        BufferUtils.copy(vertices, sourceOffset, count, byteBuffer);
        byteBuffer.position(pos);
    }

    @Override
    public void bind(final ShaderProgram shader) {
        bind(shader, null);
    }

    private int[] locationsFor(ShaderProgram shader) {
        if (shader != cachedShader) {
            final int numAttributes = attributes.size();
            if (cachedLocations == null) {
                cachedLocations = new int[numAttributes];
            }
            for (int i = 0; i < numAttributes; i++) {
                cachedLocations[i] = shader.getAttributeLocation(attributes.get(i).alias);
            }
            cachedShader = shader;
        }
        return cachedLocations;
    }

    @Override
    public void bind(final ShaderProgram shader, int[] locations) {
        if (locations == null) {
            locations = locationsFor(shader);
        }
        final int numAttributes = attributes.size();
        // GL reads this array at draw time: allocate nothing between bind and draw.
        byteBuffer.limit(buffer.limit() * 4);
        for (int i = 0; i < numAttributes; i++) {
            final VertexAttribute attribute = attributes.get(i);
            final int location = locations[i];
            if (location < 0) {
                continue;
            }
            shader.enableVertexAttribute(location);
            byteBuffer.position(attribute.offset);
            shader.setVertexAttribute(location, attribute.numComponents, attribute.type,
                    attribute.normalized, attributes.vertexSize, byteBuffer);
        }
        isBound = true;
    }

    @Override
    public void unbind(ShaderProgram shader) {
        unbind(shader, null);
    }

    @Override
    public void unbind(ShaderProgram shader, int[] locations) {
        if (locations == null) {
            locations = locationsFor(shader);
        }
        final int numAttributes = attributes.size();
        for (int i = 0; i < numAttributes; i++) {
            final int location = locations[i];
            if (location >= 0) {
                shader.disableVertexAttribute(location);
            }
        }
        isBound = false;
    }

    @Override
    public VertexAttributes getAttributes() {
        return attributes;
    }

    @Override
    public void invalidate() {
    }
}

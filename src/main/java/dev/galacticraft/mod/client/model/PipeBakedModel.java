/*
 * Copyright (c) 2019-2026 Team Galacticraft
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.galacticraft.mod.client.model;

import dev.galacticraft.mod.api.block.PipeShapedBlock;
import dev.galacticraft.mod.api.block.entity.Connected;
import dev.galacticraft.mod.api.block.entity.WalkwayHolder;
import dev.galacticraft.mod.content.block.special.fluidpipe.PipeBlockEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
/**
 * A pipe or wire, rendered from a single texture with the following layout, where {@code w} is the width of the pipe in pixels:
 * <ul>
 *     <li>columns {@code 0} to {@code w}: the side of the pipe, along the full height of the texture</li>
 *     <li>{@code w x w} at {@code (w, 0)}: the end cap, shown where the pipe ends and on the center of junctions of more than two pipes</li>
 *     <li>{@code w x w} at {@code (w, w)}: the corner, connecting the left and top edges, shown on the center of a bend</li>
 * </ul>
 */
public class PipeBakedModel implements BakedModel {
    private static final float EDGE_FACE_DEPTH = 0.001f;
    /**
     * The rotations and reflections of a tile, as {@code {uu, uv, vu, vv}}.
     */
    private static final int[][] TRANSFORMS = {
            {1, 0, 0, 1}, {-1, 0, 0, 1}, {1, 0, 0, -1}, {-1, 0, 0, -1},
            {0, 1, 1, 0}, {0, -1, 1, 0}, {0, 1, -1, 0}, {0, -1, -1, 0}
    };
    /**
     * The connections of the corner tile: left and top.
     */
    private static final int[][] CORNER = {{-1, 0}, {0, -1}};
    /**
     * How far the fluid stays inside the glass: half a pixel, so it is 3 pixels thick in a 4 pixel pipe.
     */
    private static final float FLUID_INSET = 0.5f / 16.0f;

    private final TextureAtlasSprite sprite;
    /**
     * Translucent, also for pipes that render the glass as cutout.
     */
    private final RenderMaterial fluidMaterial = RendererAccess.INSTANCE.getRenderer().materialFinder().blendMode(BlendMode.TRANSLUCENT).find();
    private final Map<Direction, Mesh> meshes;
    private final float radius;
    private final @Nullable PipeWalkwayModel walkway;

    public PipeBakedModel(Function<Material, TextureAtlasSprite> textureGetter, ResourceLocation texture, float radius, @Nullable PipeWalkwayModel walkway) {
        this.sprite = textureGetter.apply(new Material(InventoryMenu.BLOCK_ATLAS, texture));
        this.meshes = new EnumMap<>(Direction.class);
        this.radius = radius;
        this.walkway = walkway;

        float min = 0.5f - this.radius;
        float max = 0.5f + this.radius;
        float centerStart = this.centerStart();
        float centerEnd = this.centerEnd();
        float crossSectionWidth = this.crossSectionWidth();

        MeshBuilder meshBuilder = RendererAccess.INSTANCE.getRenderer().meshBuilder();
        QuadEmitter emitter = meshBuilder.getEmitter();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            emitter
                    .square(direction, min, 0.0f, max, min, min)
                    .uv(0, 0, centerEnd)
                    .uv(1, 0, 16)
                    .uv(2, crossSectionWidth, 16)
                    .uv(3, crossSectionWidth, centerEnd)
                    .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                    .color(-1, -1, -1, -1).emit();
        }
        this.meshes.put(Direction.DOWN, meshBuilder.build());

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            emitter
                    .square(direction, min, max, max, 1.0f, min)
                    .uv(0, 0, 0)
                    .uv(1, 0, centerStart)
                    .uv(2, crossSectionWidth, centerStart)
                    .uv(3, crossSectionWidth, 0)
                    .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                    .color(-1, -1, -1, -1).emit();
        }
        this.meshes.put(Direction.UP, meshBuilder.build());

        emitter
                .square(Direction.WEST, 0, min, min, max, min)
                .uv(0, 0, 0)
                .uv(1, crossSectionWidth, 0)
                .uv(2, crossSectionWidth, centerStart)
                .uv(3, 0, centerStart)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.UP, min, max, max, 1.0f, min)
                .uv(0, 0, 0)
                .uv(1, 0, centerStart)
                .uv(2, crossSectionWidth, centerStart)
                .uv(3, crossSectionWidth, 0)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.EAST, max, min, 1.0f, max, min)
                .uv(0, 0, centerEnd)
                .uv(1, crossSectionWidth, centerEnd)
                .uv(2, crossSectionWidth, 16)
                .uv(3, 0, 16)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.DOWN, min, 0.0f, max, min, min)
                .uv(0, 0, centerEnd)
                .uv(1, 0, 16)
                .uv(2, crossSectionWidth, 16)
                .uv(3, crossSectionWidth, centerEnd)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        this.meshes.put(Direction.NORTH, meshBuilder.build());

        emitter
                .square(Direction.WEST, max, min, 1.0f, max, min)
                .uv(0, 0, centerEnd)
                .uv(1, crossSectionWidth, centerEnd)
                .uv(2, crossSectionWidth, 16)
                .uv(3, 0, 16)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.EAST, 0, min, min, max, min)
                .uv(0, 0, 0)
                .uv(1, crossSectionWidth, 0)
                .uv(2, crossSectionWidth, centerStart)
                .uv(3, 0, centerStart)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.UP, min, 0.0f, max, min, min)
                .uv(0, 0, centerEnd)
                .uv(1, 0, 16)
                .uv(2, crossSectionWidth, 16)
                .uv(3, crossSectionWidth, centerEnd)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.DOWN, min, max, max, 1.0f, min)
                .uv(0, 0, 0)
                .uv(1, 0, centerStart)
                .uv(2, crossSectionWidth, centerStart)
                .uv(3, crossSectionWidth, 0)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        this.meshes.put(Direction.SOUTH, meshBuilder.build());

        emitter
                .square(Direction.NORTH, 0, min, min, max, min)
                .uv(0, 0, 0)
                .uv(1, crossSectionWidth, 0)
                .uv(2, crossSectionWidth, centerStart)
                .uv(3, 0, centerStart)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.SOUTH, max, min, 1.0f, max, min)
                .uv(0, 0, centerEnd)
                .uv(1, crossSectionWidth, centerEnd)
                .uv(2, crossSectionWidth, 16)
                .uv(3, 0, 16)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.UP, max, min, 1.0f, max, min)
                .uv(0, crossSectionWidth, centerEnd)
                .uv(1, 0, centerEnd)
                .uv(2, 0, 16)
                .uv(3, crossSectionWidth, 16)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.DOWN, max, min, 1.0f, max, min)
                .uv(0, crossSectionWidth, centerEnd)
                .uv(1, 0, centerEnd)
                .uv(2, 0, 16)
                .uv(3, crossSectionWidth, 16)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        this.meshes.put(Direction.EAST, meshBuilder.build());

        emitter
                .square(Direction.NORTH, max, min, 1.0f, max, min)
                .uv(0, 0, centerEnd)
                .uv(1, crossSectionWidth, centerEnd)
                .uv(2, crossSectionWidth, 16)
                .uv(3, 0, 16)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.SOUTH, 0, min, min, max, min)
                .uv(0, 0, 0)
                .uv(1, crossSectionWidth, 0)
                .uv(2, crossSectionWidth, centerStart)
                .uv(3, 0, centerStart)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.UP, 0, min, min, max, min)
                .uv(0, crossSectionWidth, 0)
                .uv(1, 0, 0)
                .uv(2, 0, centerStart)
                .uv(3, crossSectionWidth, centerStart)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        emitter
                .square(Direction.DOWN, 0, min, min, max, min)
                .uv(0, crossSectionWidth, 0)
                .uv(1, 0, 0)
                .uv(2, 0, centerStart)
                .uv(3, crossSectionWidth, centerStart)
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
        this.meshes.put(Direction.WEST, meshBuilder.build());
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter getter, BlockState blockState, BlockPos blockPos, Supplier<RandomSource> randomSupplier, RenderContext context) {
        var emitter = context.getEmitter();

        if (getter.getBlockEntity(blockPos) instanceof Connected pipe) {
            for (Direction direction : Direction.values()) {
                if (pipe.isConnected(direction)) {
                    this.meshes.get(direction).outputTo(emitter);
                    this.renderReducerCap(getter, blockPos, direction, emitter);
                } else if (!continuesTo(pipe, direction)) {
                    this.emitCenterQuad(pipe, emitter, direction);
                }
            }

            if (pipe instanceof PipeBlockEntity fluidPipe) {
                this.emitFluid(getter, blockPos, fluidPipe, emitter);
            }

            if (this.walkway != null && pipe instanceof WalkwayHolder holder && holder.getWalkway() != null) {
                this.walkway.emit(pipe, holder.getWalkway(), emitter, randomSupplier.get());
            }
        }
    }

    /**
     * Emits an unconnected face of the center of the pipe. A walkway counts as a connection, since the arm to it continues the pipe.
     * Junctions of more than two pipes and faces without connections in their plane show the end cap,
     * faces with connections along one axis show the side of the pipe, and faces of a bend show the corner.
     */
    private void emitCenterQuad(Connected pipe, QuadEmitter emitter, Direction face) {
        float min = 0.5f - this.radius;
        float max = 0.5f + this.radius;
        int total = 0;
        for (Direction direction : Direction.values()) {
            if (continuesTo(pipe, direction)) {
                total++;
            }
        }

        // the plane of the face, as seen in the tiles: u runs left to right, v runs top to bottom
        Direction u = face.getAxis() == Direction.Axis.Y ? Direction.EAST : face.getClockWise();
        Direction v = face.getAxis() == Direction.Axis.Y ? Direction.SOUTH : Direction.DOWN;
        List<int[]> connections = new ArrayList<>(4);
        Direction.Axis axis = null;
        for (Direction direction : new Direction[]{u, u.getOpposite(), v, v.getOpposite()}) {
            if (continuesTo(pipe, direction)) {
                connections.add(new int[]{direction == u ? 1 : direction == u.getOpposite() ? -1 : 0, direction == v ? 1 : direction == v.getOpposite() ? -1 : 0});
                axis = connections.size() == 1 || axis == direction.getAxis() ? direction.getAxis() : null;
            }
        }

        if (connections.isEmpty() || total > 2) {
            this.emitCapQuad(emitter, face, min, min, max, max, min);
        } else if (axis != null) {
            this.emitCenterSideQuad(emitter, face, axis);
        } else {
            this.emitCornerQuad(emitter, face, u, v, connections);
        }
    }

    /**
     * Emits the side of the center of a pipe that runs along the given axis.
     * The texture is mapped in the same orientation as the sides of the connections, so that the side continues from one connection to the other.
     */
    private void emitCenterSideQuad(QuadEmitter emitter, Direction face, Direction.Axis axis) {
        float min = 0.5f - this.radius;
        float max = 0.5f + this.radius;
        float width = this.crossSectionWidth();
        float start = this.centerStart();
        float end = this.centerEnd();
        emitter.square(face, min, min, max, max, min);
        boolean alongX = switch (axis) {
            case X -> true;
            case Y -> false;
            case Z -> face.getAxis() == Direction.Axis.X;
        };
        if (alongX) {
            boolean flipped = axis == Direction.Axis.X && face.getAxis() == Direction.Axis.Y;
            emitter.uv(0, flipped ? width : 0, start)
                    .uv(1, flipped ? 0 : width, start)
                    .uv(2, flipped ? 0 : width, end)
                    .uv(3, flipped ? width : 0, end);
        } else {
            emitter.uv(0, 0, start)
                    .uv(1, 0, end)
                    .uv(2, width, end)
                    .uv(3, width, start);
        }
        emitter.spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
    }

    /**
     * Emits the corner tile, rotated and/or mirrored so that its edges line up with the given connections.
     */
    private void emitCornerQuad(QuadEmitter emitter, Direction face, Direction u, Direction v, List<int[]> connections) {
        float min = 0.5f - this.radius;
        float max = 0.5f + this.radius;
        float width = this.crossSectionWidth();
        int[] transform = findTransform(connections);
        emitter.square(face, min, min, max, max, min);
        for (int i = 0; i < 4; i++) {
            float pu = planeCoordinate(emitter, i, u, min, max);
            float pv = planeCoordinate(emitter, i, v, min, max);
            float tu = transform[0] * pu + transform[1] * pv;
            float tv = transform[2] * pu + transform[3] * pv;
            emitter.uv(i, width + (tu + 1) * 0.5f * width, width + (tv + 1) * 0.5f * width);
        }
        emitter.spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
    }

    /**
     * {@return the transform that rotates and/or mirrors the given connections onto the connections of the corner tile}
     */
    private static int[] findTransform(List<int[]> connections) {
        for (int[] transform : TRANSFORMS) {
            boolean matches = true;
            for (int[] connection : connections) {
                int tu = transform[0] * connection[0] + transform[1] * connection[1];
                int tv = transform[2] * connection[0] + transform[3] * connection[1];
                boolean found = false;
                for (int[] edge : CORNER) {
                    if (edge[0] == tu && edge[1] == tv) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return transform;
            }
        }
        throw new IllegalStateException("No transform found for the connections of a pipe");
    }

    /**
     * {@return the position of a vertex in the given direction, from -1 to 1 across the center of the pipe}
     */
    private static float planeCoordinate(QuadEmitter emitter, int vertex, Direction direction, float min, float max) {
        float position = switch (direction.getAxis()) {
            case X -> emitter.x(vertex);
            case Y -> emitter.y(vertex);
            case Z -> emitter.z(vertex);
        };
        float coordinate = (position - min) / (max - min) * 2 - 1;
        return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? coordinate : -coordinate;
    }

    /**
     * Emits a core of the fluid flowing through the pipe inside the glass: a center with an arm to every connected side.
     * It is part of the block model rather than drawn by a block entity renderer,
     * so it is sorted together with the glass and always ends up behind the front of it.
     */
    private void emitFluid(BlockAndTintGetter getter, BlockPos blockPos, PipeBlockEntity pipe, QuadEmitter emitter) {
        FluidVariant fluid = pipe.getDisplayedFluid();
        if (fluid.isBlank()) return;

        TextureAtlasSprite fluidSprite = FluidVariantRendering.getSprite(fluid);
        if (fluidSprite == null) {
            // Like MachineLib, show fluids without a texture as tinted water
            fluidSprite = FluidVariantRendering.getSprite(FluidVariant.of(Fluids.WATER));
            if (fluidSprite == null) return;
        }
        // Fluid colors don't always include alpha, the transparency comes from the texture
        int color = FastColor.ARGB32.opaque(FluidVariantRendering.getColor(fluid, getter, blockPos));

        float min = 0.5f - this.radius + FLUID_INSET;
        float max = 0.5f + this.radius - FLUID_INSET;

        // The center, with an opening on every connected side
        List<Direction> connected = new ArrayList<>(6);
        for (Direction face : Direction.values()) {
            if (pipe.isConnected(face)) {
                connected.add(face);
            } else {
                this.emitFluidFace(emitter, face, min, min, min, max, max, max, fluidSprite, color);
            }
        }

        // An arm from the center to the edge of the block for every connection, without its ends
        for (Direction direction : connected) {
            float x0 = direction == Direction.WEST ? 0 : direction == Direction.EAST ? max : min;
            float x1 = direction == Direction.WEST ? min : direction == Direction.EAST ? 1 : max;
            float y0 = direction == Direction.DOWN ? 0 : direction == Direction.UP ? max : min;
            float y1 = direction == Direction.DOWN ? min : direction == Direction.UP ? 1 : max;
            float z0 = direction == Direction.NORTH ? 0 : direction == Direction.SOUTH ? max : min;
            float z1 = direction == Direction.NORTH ? min : direction == Direction.SOUTH ? 1 : max;
            for (Direction face : Direction.values()) {
                if (face.getAxis() != direction.getAxis()) {
                    this.emitFluidFace(emitter, face, x0, y0, z0, x1, y1, z1, fluidSprite, color);
                }
            }
        }
    }

    private void emitFluidFace(QuadEmitter emitter, Direction face, float x0, float y0, float z0, float x1, float y1, float z1, TextureAtlasSprite fluidSprite, int color) {
        // The texture is mapped by position, so it lines up between the center and the arms
        float u0, u1, v0, v1;
        switch (face.getAxis()) {
            case Y -> { u0 = x0; u1 = x1; v0 = z0; v1 = z1; }
            case Z -> { u0 = x0; u1 = x1; v0 = 1 - y1; v1 = 1 - y0; }
            default -> { u0 = z0; u1 = z1; v0 = 1 - y1; v1 = 1 - y0; }
        }
        u0 = fluidSprite.getU(u0);
        u1 = fluidSprite.getU(u1);
        v0 = fluidSprite.getV(v0);
        v1 = fluidSprite.getV(v1);

        switch (face) {
            case DOWN -> emitter.pos(0, x0, y0, z1).uv(0, u0, v1).pos(1, x0, y0, z0).uv(1, u0, v0)
                    .pos(2, x1, y0, z0).uv(2, u1, v0).pos(3, x1, y0, z1).uv(3, u1, v1);
            case UP -> emitter.pos(0, x0, y1, z0).uv(0, u0, v0).pos(1, x0, y1, z1).uv(1, u0, v1)
                    .pos(2, x1, y1, z1).uv(2, u1, v1).pos(3, x1, y1, z0).uv(3, u1, v0);
            case NORTH -> emitter.pos(0, x1, y1, z0).uv(0, u1, v0).pos(1, x1, y0, z0).uv(1, u1, v1)
                    .pos(2, x0, y0, z0).uv(2, u0, v1).pos(3, x0, y1, z0).uv(3, u0, v0);
            case SOUTH -> emitter.pos(0, x0, y1, z1).uv(0, u0, v0).pos(1, x0, y0, z1).uv(1, u0, v1)
                    .pos(2, x1, y0, z1).uv(2, u1, v1).pos(3, x1, y1, z1).uv(3, u1, v0);
            case WEST -> emitter.pos(0, x0, y1, z0).uv(0, u0, v0).pos(1, x0, y0, z0).uv(1, u0, v1)
                    .pos(2, x0, y0, z1).uv(2, u1, v1).pos(3, x0, y1, z1).uv(3, u1, v0);
            case EAST -> emitter.pos(0, x1, y1, z1).uv(0, u1, v0).pos(1, x1, y0, z1).uv(1, u1, v1)
                    .pos(2, x1, y0, z0).uv(2, u0, v1).pos(3, x1, y1, z0).uv(3, u0, v0);
        }
        emitter.nominalFace(face).material(this.fluidMaterial).color(color, color, color, color).emit();
    }

    /**
     * {@return whether the pipe continues on the given side: to a connection, or to its walkway}
     */
    private static boolean continuesTo(Connected pipe, Direction direction) {
        return pipe.isConnected(direction) || pipe instanceof WalkwayHolder holder && holder.getWalkway() == direction;
    }

    private void renderReducerCap(BlockAndTintGetter getter, BlockPos blockPos, Direction direction, QuadEmitter emitter) {
        BlockState neighborState = getter.getBlockState(blockPos.relative(direction));
        if (!(neighborState.getBlock() instanceof PipeShapedBlock<?> neighborPipe)) {
            return;
        }

        float neighborRadius = neighborPipe.getRadius();
        if (neighborRadius >= this.radius) {
            return;
        }

        float minOuter = 0.5f - this.radius;
        float maxOuter = 0.5f + this.radius;
        float minInner = 0.5f - neighborRadius;
        float maxInner = 0.5f + neighborRadius;
        this.emitCapQuad(emitter, direction, minInner, minInner, maxInner, maxInner, EDGE_FACE_DEPTH);
        this.emitCapQuad(emitter, direction, minOuter, minOuter, maxOuter, minInner, EDGE_FACE_DEPTH);
        this.emitCapQuad(emitter, direction, minOuter, maxInner, maxOuter, maxOuter, EDGE_FACE_DEPTH);
        this.emitCapQuad(emitter, direction, minOuter, minInner, minInner, maxInner, EDGE_FACE_DEPTH);
        this.emitCapQuad(emitter, direction, maxInner, minInner, maxOuter, maxInner, EDGE_FACE_DEPTH);
    }

    private void emitCapQuad(QuadEmitter emitter, Direction direction, float minX, float minY, float maxX, float maxY, float depth) {
        emitter
                .square(direction, minX, minY, maxX, maxY, depth)
                .uv(0, this.capU(minX), this.capV(minY))
                .uv(1, this.capU(minX), this.capV(maxY))
                .uv(2, this.capU(maxX), this.capV(maxY))
                .uv(3, this.capU(maxX), this.capV(minY))
                .spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV)
                .color(-1, -1, -1, -1).emit();
    }

    // the end cap is the tile next to the side of the pipe
    private float capU(float value) {
        return (value - (0.5f - this.radius)) * 16.0f + this.crossSectionWidth();
    }

    private float capV(float value) {
        return (value - (0.5f - this.radius)) * 16.0f;
    }

    private float centerStart() {
        return (0.5f - this.radius) * 16.0f;
    }

    private float centerEnd() {
        return (0.5f + this.radius) * 16.0f;
    }

    private float crossSectionWidth() {
        return this.centerEnd() - this.centerStart();
    }

    @Override
    public void emitItemQuads(ItemStack itemStack, Supplier<RandomSource> randomSupplier, RenderContext context) {
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState blockState, @Nullable Direction direction, RandomSource random) {
        return Collections.emptyList();
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public @NotNull TextureAtlasSprite getParticleIcon() {
        return this.sprite;
    }

    @Override
    public @NotNull ItemTransforms getTransforms() {
        return ItemTransforms.NO_TRANSFORMS;
    }

    @Override
    public @NotNull ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

    public enum ColorTransform implements RenderContext.QuadTransform {
        INSTANCE;
        private TextureAtlasSprite sprite = null;

        public void setSprite(TextureAtlasSprite sprite) {
            this.sprite = sprite;
        }

        @Override
        public boolean transform(MutableQuadView quad) {
            quad.spriteBake(this.sprite, MutableQuadView.BAKE_NORMALIZED & MutableQuadView.BAKE_LOCK_UV);
            return true;
        }
    }
}
/*
 * Voxel world extension (c) 2012 Steven Brooker <immortius@gmail.com>
 *
 * Bullet Continuous Collision Detection and Physics Library
 * Copyright (c) 2003-2008 Erwin Coumans  http://www.bulletphysics.com/
 *
 * This software is provided 'as-is', without any express or implied warranty.
 * In no event will the authors be held liable for any damages arising from
 * the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 */

package com.bulletphysics.collision.dispatch;

import com.bulletphysics.collision.broadphase.BroadphaseNativeType;
import com.bulletphysics.collision.broadphase.CollisionAlgorithm;
import com.bulletphysics.collision.broadphase.CollisionAlgorithmConstructionInfo;
import com.bulletphysics.collision.broadphase.DispatcherInfo;
import com.bulletphysics.collision.narrowphase.PersistentManifold;
import com.bulletphysics.collision.shapes.BoxShape;
import com.bulletphysics.collision.shapes.CollisionShape;
import com.bulletphysics.collision.shapes.voxel.VoxelInfo;
import com.bulletphysics.collision.shapes.voxel.VoxelWorldShape;
import com.bulletphysics.linearmath.IntUtil;
import com.bulletphysics.linearmath.Transform;
import com.bulletphysics.util.ObjectArrayList;
import com.bulletphysics.util.ObjectPool;
import cz.advel.stack.Stack;

import javax.vecmath.*;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Immortius
 */
public class VoxelWorldCollisionAlgorithm extends CollisionAlgorithm {

    private Map<Tuple3i, CollisionAlgorithm> collisionAlgMap = new HashMap<Tuple3i, CollisionAlgorithm>();
    private boolean isSwapped;

    public void init(CollisionAlgorithmConstructionInfo ci, CollisionObject body0, CollisionObject body1, boolean isSwapped) {
        super.init(ci);

        this.isSwapped = isSwapped;
    }

    @Override
    public void destroy() {
        for (CollisionAlgorithm alg : collisionAlgMap.values()) {
            dispatcher.freeCollisionAlgorithm(alg);
        }
        collisionAlgMap.clear();
    }

    @Override
    public void processCollision(CollisionObject body0, CollisionObject body1, DispatcherInfo dispatchInfo, ManifoldResult resultOut) {
        CollisionObject colObj = isSwapped ? body1 : body0;
        CollisionObject otherObj = isSwapped ? body0 : body1;
        assert (colObj.getCollisionShape().getShapeType() == BroadphaseNativeType.INVALID_SHAPE_PROXYTYPE);

        VoxelWorldShape worldShape = (VoxelWorldShape) colObj.getCollisionShape();

        Transform otherObjTransform = new Transform();
        otherObj.getWorldTransform(otherObjTransform);
        Vector3f aabbMin = Stack.alloc(Vector3f.class);
        Vector3f aabbMax = Stack.alloc(Vector3f.class);
        otherObj.getCollisionShape().getAabb(otherObjTransform, aabbMin, aabbMax);
        Matrix4f otherObjMatrix = new Matrix4f();
        otherObjTransform.getMatrix(otherObjMatrix);
        Vector3f otherObjPos = new Vector3f();
        otherObjMatrix.get(otherObjPos);

        Tuple3i regionMin = new Point3i(IntUtil.floorToInt(aabbMin.x + 0.5f), IntUtil.floorToInt(aabbMin.y + 0.5f), IntUtil.floorToInt(aabbMin.z + 0.5f));
        Tuple3i regionMax = new Point3i(IntUtil.floorToInt(aabbMax.x + 0.5f), IntUtil.floorToInt(aabbMax.y + 0.5f), IntUtil.floorToInt(aabbMax.z + 0.5f));

        Transform orgTrans = Stack.alloc(Transform.class);
        colObj.getWorldTransform(orgTrans);

        Transform newChildWorldTrans = Stack.alloc(Transform.class);
        Matrix4f childMat = Stack.alloc(Matrix4f.class);

        Matrix3f rot = Stack.alloc(Matrix3f.class);
        rot.setIdentity();

        for (int x = regionMin.x; x <= regionMax.x; ++x) {
            for (int y = regionMin.y; y <= regionMax.y; ++y) {
                for (int z = regionMin.z; z <= regionMax.z; ++z) {
                    VoxelInfo childInfo = worldShape.getWorld().getCollisionShapeAt(x, y, z);
                    Point3i blockPos = new Point3i(x, y, z);
                    if (childInfo.isBlocking()) {
                        colObj.internalSetTemporaryCollisionShape(childInfo.getCollisionShape());

                        CollisionAlgorithm alg = collisionAlgMap.get(blockPos);
                        if (alg == null) {
                            alg = dispatcher.findAlgorithm(colObj, otherObj);
                            collisionAlgMap.put(blockPos, alg);
                        }

                        childMat.set(rot, new Vector3f(x, y, z), 1.0f);
                        newChildWorldTrans.set(childMat);
                        colObj.setWorldTransform(newChildWorldTrans);
                        colObj.setInterpolationWorldTransform(newChildWorldTrans);
                        colObj.setUserPointer(blockPos);

                        alg.processCollision(colObj, otherObj, dispatchInfo, resultOut);
                    } else {
                        CollisionAlgorithm alg = collisionAlgMap.remove(blockPos);
                        if (alg != null) {
                            dispatcher.freeCollisionAlgorithm(alg);
                        }
                    }
                }
            }
        }
        colObj.internalSetTemporaryCollisionShape(worldShape);
        colObj.setWorldTransform(orgTrans);
        colObj.setInterpolationWorldTransform(orgTrans);
    }

    @Override
    public float calculateTimeOfImpact(CollisionObject body0, CollisionObject body1, DispatcherInfo dispatchInfo, ManifoldResult resultOut) {
        // TODO: Implement this? Although not used for discrete dynamics
        /*PerformanceMonitor.startActivity("World Calculate Time Of Impact");
        CollisionObject colObj = isSwapped ? body1 : body0;
        CollisionObject otherObj = isSwapped ? body0 : body1;

        assert (colObj.getCollisionShape().getShapeType() == BroadphaseNativeType.INVALID_SHAPE_PROXYTYPE);

        WorldShape worldShape = (WorldShape) colObj.getCollisionShape();

        Transform otherObjTransform = new Transform();
        Vector3f otherLinearVelocity = new Vector3f();
        Vector3f otherAngularVelocity = new Vector3f();
        otherObj.getInterpolationWorldTransform(otherObjTransform);
        otherObj.getInterpolationLinearVelocity(otherLinearVelocity);
        otherObj.getInterpolationAngularVelocity(otherAngularVelocity);
        Vector3f aabbMin = Stack.alloc(Vector3f.class);
        Vector3f aabbMax = Stack.alloc(Vector3f.class);
        otherObj.getCollisionShape().getAabb(otherObjTransform, aabbMin, aabbMax);

        Region3i region = Region3i.createFromMinMax(new Vector3i(aabbMin, 0.5f), new Vector3i(aabbMax, 0.5f));

        Transform orgTrans = Stack.alloc(Transform.class);
        Transform childTrans = Stack.alloc(Transform.class);
        float hitFraction = 1f;

        Matrix3f rot = new Matrix3f();
        rot.setIdentity();

        for (Vector3i blockPos : region) {
            Block block = worldShape.getWorld().getBlock(blockPos);
            if (block.isPenetrable()) continue;

            // recurse, using each shape within the block.
            CollisionShape childShape = defaultBox;

            // backup
            colObj.getWorldTransform(orgTrans);

            childTrans.set(new Matrix4f(rot, blockPos.toVector3f(), 1.0f));
            colObj.setWorldTransform(childTrans);

            // the contactpoint is still projected back using the original inverted worldtrans
            CollisionShape tmpShape = colObj.getCollisionShape();
            colObj.internalSetTemporaryCollisionShape(childShape);
            colObj.setUserPointer(blockPos);

            CollisionAlgorithm collisionAlg = collisionAlgorithmFactory.dispatcher1.findAlgorithm(colObj, otherObj);
            usedCollisionAlgorithms.add(collisionAlg);
            float frac = collisionAlg.calculateTimeOfImpact(colObj, otherObj, dispatchInfo, resultOut);
            if (frac < hitFraction) {
                hitFraction = frac;
            }

            // revert back
            colObj.internalSetTemporaryCollisionShape(tmpShape);
            colObj.setWorldTransform(orgTrans);
        }
        PerformanceMonitor.endActivity();
        return hitFraction;        */
        return 1.0f;
    }

    @Override
    public void getAllContactManifolds(ObjectArrayList<PersistentManifold> manifoldArray) {
        for (CollisionAlgorithm alg : collisionAlgMap.values()) {
            alg.getAllContactManifolds(manifoldArray);
        }
    }

    ////////////////////////////////////////////////////////////////////////////

    public static class CreateFunc extends CollisionAlgorithmCreateFunc {
        private final ObjectPool<VoxelWorldCollisionAlgorithm> pool = ObjectPool.get(VoxelWorldCollisionAlgorithm.class);

        @Override
        public CollisionAlgorithm createCollisionAlgorithm(CollisionAlgorithmConstructionInfo ci, CollisionObject body0, CollisionObject body1) {
            VoxelWorldCollisionAlgorithm algo = pool.get();
            algo.init(ci, body0, body1, false);
            return algo;
        }

        @Override
        public void releaseCollisionAlgorithm(CollisionAlgorithm algo) {
            pool.release((VoxelWorldCollisionAlgorithm)algo);
        }
    };

    public static class SwappedCreateFunc extends CollisionAlgorithmCreateFunc {
        private final ObjectPool<VoxelWorldCollisionAlgorithm> pool = ObjectPool.get(VoxelWorldCollisionAlgorithm.class);

        @Override
        public CollisionAlgorithm createCollisionAlgorithm(CollisionAlgorithmConstructionInfo ci, CollisionObject body0, CollisionObject body1) {
            VoxelWorldCollisionAlgorithm algo = pool.get();
            algo.init(ci, body0, body1, true);
            return algo;
        }

        @Override
        public void releaseCollisionAlgorithm(CollisionAlgorithm algo) {
            pool.release((VoxelWorldCollisionAlgorithm)algo);
        }
    };
}
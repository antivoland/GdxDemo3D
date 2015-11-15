/*******************************************************************************
 * Copyright 2015 See AUTHORS file.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/


package com.mygdx.game.pathfinding;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ai.pfa.indexed.IndexedAStarPathFinder;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.physics.bullet.collision.btBvhTriangleMeshShape;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.collision.btTriangleIndexVertexArray;
import com.badlogic.gdx.physics.bullet.collision.btTriangleRaycastCallback;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Bits;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.mygdx.game.utilities.GeometryUtils;

/**
 * @author jsjolund
 */
public class NavMesh implements Disposable {

	public static final String tag = "NavMesh";

	public final NavMeshGraph graph;

	private final btBvhTriangleMeshShape collisionShape;
	private final NavMeshRaycastCallback raycastCallback;
	private final NavMeshHeuristic heuristic;
	private final IndexedAStarPathFinder<Triangle> pathFinder;

	// Temporary memory used by various methods for calculations
	private final FloatArray tmpFloatArrayGetRandomTriangle = new FloatArray();
	private final Array<Triangle> tmpTriArrayGetRandomTriangle = new Array<Triangle>();
	private final Bits tmpBitsGetRandomTriangle = new Bits();
	private final Bits tmpBitsVerticalRayTest = new Bits();
	private final Vector3 tmpGetClosestTriangle = new Vector3();
	private final Vector3 tmpGetClosestPoint = new Vector3();
	private final Ray tmpRayGetClosestPoint = new Ray();
	private final Plane tmpPlaneGetClosestPoint = new Plane();
	private final Vector3 tmpVerticalRayTest1 = new Vector3();
	private final Vector3 tmpVerticalRayTest2 = new Vector3();
	private final Ray tmpRayVerticalRayTest = new Ray();
	private final Vector3 tmpVecgetClosestValidPointAt = new Vector3();
	private final Vector3 tmpRayTestRayTo = new Vector3();
	private final Vector3 tmpRayTestRayFrom = new Vector3();
	private final Vector3 navMeshRayFrom = new Vector3();
	private final Vector3 navMeshRayTo = new Vector3();

	public NavMesh(Model model) {
		btTriangleIndexVertexArray vertexArray = new btTriangleIndexVertexArray(model.meshParts);
		collisionShape = new btBvhTriangleMeshShape(vertexArray, true);
		raycastCallback = new NavMeshRaycastCallback(navMeshRayFrom, navMeshRayTo);
		raycastCallback.setFlags(btTriangleRaycastCallback.EFlags.kF_FilterBackfaces);
		graph = new NavMeshGraph(model);
		pathFinder = new IndexedAStarPathFinder<Triangle>(graph);
		heuristic = new NavMeshHeuristic();
	}

	public btCollisionShape getShape() {
		return collisionShape;
	}

	@Override
	public void dispose() {
		collisionShape.dispose();
		raycastCallback.dispose();
	}

	/**
	 * Get the triangle which this ray intersects. Returns null if no triangle is intersected.
	 *
	 * @param ray
	 * @param distance
	 * @param allowedMeshParts
	 * @return
	 */
	public Triangle rayTest(Ray ray, float distance, Bits allowedMeshParts) {
		Triangle hitTriangle = null;

		tmpRayTestRayFrom.set(ray.origin);
		tmpRayTestRayTo.set(ray.direction).scl(distance).add(tmpRayTestRayFrom);
		raycastCallback.setHitFraction(1);
		raycastCallback.clearReport();
		raycastCallback.setFrom(tmpRayTestRayFrom);
		raycastCallback.setTo(tmpRayTestRayTo);
		raycastCallback.setAllowedMeshPartIndices(allowedMeshParts);
		collisionShape.performRaycast(raycastCallback, tmpRayTestRayFrom, tmpRayTestRayTo);

		if (raycastCallback.triangleIndex != -1) {
			hitTriangle = graph.getTriangleFromMeshPart(raycastCallback.partId, raycastCallback.triangleIndex);
		}
		return hitTriangle;
	}

	/**
	 * Calculate a triangle graph path between two triangles which are intersected by the rays.
	 *
	 * @param fromRay
	 * @param toRay
	 * @param allowedMeshParts
	 * @param distance
	 * @param path
	 * @return
	 */
	public boolean getPath(Ray fromRay, Ray toRay, Bits allowedMeshParts,
						   float distance, NavMeshGraphPath path) {

		Triangle fromTri = rayTest(fromRay, distance, allowedMeshParts);
		if (fromTri == null) {
			Gdx.app.debug(tag, "From triangle not found.");
			return false;
		}
		Vector3 fromPoint = new Vector3();
		Intersector.intersectRayTriangle(fromRay, fromTri.a, fromTri.b, fromTri.c, fromPoint);

		return getPath(fromTri, fromPoint, toRay, allowedMeshParts, distance, path);
	}

	/**
	 * Calculate a triangle graph path from a start triangle to the triangle which is intersected by a ray.
	 *
	 * @param fromTri
	 * @param fromPoint
	 * @param toRay
	 * @param allowedMeshParts
	 * @param distance
	 * @param path
	 * @return
	 */
	public boolean getPath(Triangle fromTri, Vector3 fromPoint, Ray toRay, Bits allowedMeshParts,
						   float distance, NavMeshGraphPath path) {
		Triangle toTri = rayTest(toRay, distance, allowedMeshParts);
		if (toTri == null) {
			Gdx.app.debug(tag, "To triangle not found.");
			return false;
		}
		Vector3 toPoint = new Vector3();
		Intersector.intersectRayTriangle(toRay, toTri.a, toTri.b, toTri.c, toPoint);

		return getPath(fromTri, fromPoint, toTri, toPoint, path);
	}

	/**
	 * Calculate a triangle graph path between two triangles.
	 *
	 * @param fromTri
	 * @param fromPoint
	 * @param toTri
	 * @param toPoint
	 * @param path
	 * @return
	 */
	public boolean getPath(Triangle fromTri, Vector3 fromPoint,
						   Triangle toTri, Vector3 toPoint,
						   NavMeshGraphPath path) {
		path.clear();
		if (pathFinder.searchConnectionPath(fromTri, toTri, heuristic, path)) {
			path.start = new Vector3(fromPoint);
			path.end = new Vector3(toPoint);
			path.startTri = fromTri;
			return true;
		}
		Gdx.app.debug(tag, "Path not found.");
		return false;
	}

	/**
	 * Get a random triangle anywhere on the navigation mesh.
	 * The probability distribution is even in world space, as opposed to triangle index,
	 * meaning large triangles will be chosen more often than small ones.
	 */
	public Triangle getRandomTriangle() {
		tmpBitsGetRandomTriangle.clear();
		for (int i = 0; i < graph.getMeshPartCount(); i++) {
			tmpBitsGetRandomTriangle.set(i);
		}
		return getRandomTriangle(tmpBitsGetRandomTriangle);
	}

	/**
	 * Get a random triangle on the navigation mesh, on any of the allowed mesh parts.
	 * The probability distribution is even in world space, as opposed to triangle index,
	 * meaning large triangles will be chosen more often than small ones.
	 * <p/>
	 * Example usage, to get a random point on the second navigation mesh part:
	 * allowedMeshParts.clear();
	 * allowedMeshParts.set(1);
	 * Triangle randomTri = navmesh.getRandomTriangle(allowedMeshParts);
	 * Vector3 randomPoint = new Vector3();
	 * randomTri.getRandomPoint(randomPoint);
	 *
	 * @param allowedMeshParts Bits representing allowed mesh part indices.
	 * @return A random triangle.
	 */
	public Triangle getRandomTriangle(Bits allowedMeshParts) {
		tmpFloatArrayGetRandomTriangle.clear();
		tmpFloatArrayGetRandomTriangle.ordered = true;
		tmpTriArrayGetRandomTriangle.clear();
		tmpTriArrayGetRandomTriangle.ordered = true;

		// To get a uniform distribution over the triangles in the mesh parts
		// we must take areas of the triangles into account.
		for (int mpIndex = 0; mpIndex < graph.getMeshPartCount(); mpIndex++) {
			if (allowedMeshParts.get(mpIndex)) {
				for (int triIndex = 0; triIndex < graph.getTriangleCount(mpIndex); triIndex++) {
					Triangle tri = graph.getTriangleFromMeshPart(mpIndex, triIndex);
					float integratedArea = 0;
					if (tmpFloatArrayGetRandomTriangle.size > 0) {
						integratedArea = tmpFloatArrayGetRandomTriangle.get(tmpFloatArrayGetRandomTriangle.size - 1);
					}
					tmpFloatArrayGetRandomTriangle.add(integratedArea + tri.area());
					tmpTriArrayGetRandomTriangle.add(tri);
				}
			}
		}
		if (tmpFloatArrayGetRandomTriangle.size == 0) {
			return null;
		}
		float r = MathUtils.random(0f, tmpFloatArrayGetRandomTriangle.get(tmpFloatArrayGetRandomTriangle.size - 1));
		int i;
		for (i = 0; i < tmpFloatArrayGetRandomTriangle.size; i++) {
			if (r <= tmpFloatArrayGetRandomTriangle.get(i)) {
				break;
			}
		}
		return tmpTriArrayGetRandomTriangle.get(i);
	}

	/**
	 * Make a ray test at this point, using a ray spanning from far up in the sky, to far down in the ground.
	 *
	 * @param testPoint The test point
	 * @param out The point of intersection between ray and triangle
	 * @param meshPartIndex Which mesh parts to test.
	 * @return The triangle, or null if ray did not hit any triangles.
	 */
	public Triangle verticalRayTest(Vector3 testPoint, Vector3 out, int meshPartIndex) {
		tmpBitsVerticalRayTest.clear();
		tmpBitsVerticalRayTest.set(meshPartIndex);
		return verticalRayTest(testPoint, out, tmpBitsVerticalRayTest);
	}

	/**
	 * Make a ray test at this point, using a ray spanning from far up in the sky, to far down in the ground.
	 *
	 * @param testPoint The test point
	 * @param out The point of intersection between ray and triangle
	 * @param allowedMeshParts Which mesh parts to test.
	 * @return The triangle, or null if ray did not hit any triangles.
	 */
	public Triangle verticalRayTest(Vector3 testPoint, Vector3 out, Bits allowedMeshParts) {
		tmpRayVerticalRayTest.set(tmpVerticalRayTest1.set(Vector3.Y).scl(500).add(testPoint), tmpVerticalRayTest2.set(Vector3.Y).scl(-1));
		Triangle hitTri = rayTest(tmpRayVerticalRayTest, 1000, allowedMeshParts);
		if (hitTri == null) {
			// TODO: Perhaps this should be Nan?
			out.set(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
			return null;
		} else {

			Intersector.intersectRayTriangle(tmpRayVerticalRayTest, hitTri.a, hitTri.b, hitTri.c, out);
			return hitTri;
		}
	}

	/**
	 * Ray tests the navmesh along up/down axis, if no triangles are found, it makes an
	 * exhaustive search of all triangles on the navmesh.
	 *
	 * TODO: Exhaustive search is somewhat expensive depending on amount of
	 * triangles in navmesh, maybe something like quadtrees can be used?
	 *
	 * @param fromPoint
	 * @param closestPoint
	 * @return
	 */
	public Triangle getClosestTriangle(Vector3 fromPoint, Vector3 closestPoint) {
		Triangle fromTri = null;
		float minDst2 = Float.POSITIVE_INFINITY;
		// TODO: To use this, one must specify valid navmesh parts.
//		for (int meshPartIndex = 0; meshPartIndex < graph.getMeshPartCount(); meshPartIndex++) {
//			Triangle tri = verticalRayTest(fromPoint, tmpGetClosestTriangle, meshPartIndex);
//			float dst2 = fromPoint.dst2(tmpGetClosestTriangle);
//			if (dst2 < minDst2) {
//				minDst2 = dst2;
//				fromTri = tri;
//				closestPoint.set(tmpGetClosestTriangle);
//			}
//		}
		// Exhaustive scan through all the tris to find the closest tri and point
		if (fromTri == null) {
			for (int i = 0; i < graph.getNodeCount(); i++) {
				Triangle tri = graph.getTriangleFromGraphIndex(i);
				float dst2 = getClosestPoint(tri, fromPoint, tmpGetClosestTriangle);

				if (dst2 < minDst2) {
					minDst2 = dst2;
					fromTri = tri;
					closestPoint.set(tmpGetClosestTriangle);
				}
			}
		}
		return fromTri;
	}

	/**
	 * Find the closest point on the triangle, given a measure point.
	 *
	 * @param tri The triangle
	 * @param point The measure point
	 * @param out Output for the closest point
	 * @return The closest distance squared, between the triangle and measure point.
	 */
	public float getClosestPoint(Triangle tri, Vector3 point, Vector3 out) {
		tmpPlaneGetClosestPoint.set(tri.a, tri.b, tri.c);
		float minDst = tmpPlaneGetClosestPoint.distance(point);
		Vector3 towardsPlane = tmpGetClosestPoint.set(tmpPlaneGetClosestPoint.normal).scl(minDst).add(point);
		tmpRayGetClosestPoint.origin.set(point);
		tmpRayGetClosestPoint.direction.set(point).sub(towardsPlane);
		if (Intersector.intersectRayTriangle(tmpRayGetClosestPoint, tri.a, tri.b, tri.c, out)) {
			minDst = minDst * minDst;
		} else {
			Vector3 nearest = tmpGetClosestPoint;
			float dst2;
			minDst = Float.POSITIVE_INFINITY;
			if ((dst2 = GeometryUtils.nearestSegmentPointSquareDistance(nearest, tri.a, tri.b, point)) < minDst) {
				out.set(nearest);
				minDst = dst2;
			}
			if ((dst2 = GeometryUtils.nearestSegmentPointSquareDistance(nearest, tri.b, tri.c, point)) < minDst) {
				out.set(nearest);
				minDst = dst2;
			}
			if ((dst2 = GeometryUtils.nearestSegmentPointSquareDistance(nearest, tri.c, tri.a, point)) < minDst) {
				out.set(nearest);
				minDst = dst2;
			}
		}
		return minDst;
	}

	/**
	 * Find triangle and point on the navmesh closest to fromPoint.
	 *
	 * @param fromPoint
	 * @param direction
	 * @param radius
	 * @param out
	 * @return
	 */
	public Triangle getClosestValidPointAt(Vector3 fromPoint, Vector3 direction,
										   float radius, Vector3 out) {

		// TODO: Continue here
		Vector3 originalTargetPoint = tmpVecgetClosestValidPointAt.set(direction).nor().scl(radius).add(fromPoint);
		return getClosestTriangle(originalTargetPoint, out);
	}


}

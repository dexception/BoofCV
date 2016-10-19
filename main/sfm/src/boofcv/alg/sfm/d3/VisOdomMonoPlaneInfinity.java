/*
 * Copyright (c) 2011-2016, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
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
 */

package boofcv.alg.sfm.d3;

import boofcv.abst.feature.tracker.PointTrack;
import boofcv.abst.feature.tracker.PointTracker;
import boofcv.alg.distort.LensDistortionOps;
import boofcv.alg.sfm.overhead.CameraPlaneProjection;
import boofcv.struct.calib.CameraPinholeRadial;
import boofcv.struct.distort.Point2Transform2_F64;
import boofcv.struct.image.ImageBase;
import boofcv.struct.sfm.PlanePtPixel;
import georegression.geometry.GeometryMath_F64;
import georegression.metric.UtilAngle;
import georegression.struct.point.Point2D_F64;
import georegression.struct.point.Vector3D_F64;
import georegression.struct.se.Se2_F64;
import georegression.struct.se.Se3_F64;
import georegression.transform.se.SePointOps_F64;
import org.ddogleg.fitting.modelset.ModelMatcher;
import org.ddogleg.struct.FastQueue;
import org.ddogleg.struct.GrowQueue_F64;
import org.ejml.data.DenseMatrix64F;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Estimates camera ego-motion by assuming the camera is viewing a flat plane and that off plane points are at infinity.
 * These assumptions are often true for robots traveling over flat terrain outdoors.  There are no assumptions for
 * orientation of the camera, e.g. it could be upside down and at an angle relative to the plane.
 * </p>
 * A point feature tracker is used to extract sparse optical flow from the image sequence.  Bad tracks are removed
 * first through the use of geometric constraints and then through robust estimation techniques which search for
 * maximum inlier sets. Before a track is discarded it has to be not used for X frames in a row, where X is user
 * specified.  Determination of inliers is done through a user specified threshold in pixels.
 * </p>
 * Points on the plane are used to estimate translation and rotation, while points at infinity are just used to estimate
 * rotation.  The two rotation estimates are merged together using a weighted vector average. Motion estimation does not
 * require any off plane points at infinity, but does require points on the plane.  All motion estimation is done
 * internally in 2D, but can be converted into 3D.
 * </p>
 * It is possible to turn on a more rigid test for off plane points at infinity.  If 'strictFar' is set to true then
 * any points which contains motions that could not be generated by a rotation about the y-axis are discarded.  This
 * appears to be too strict and discard an excessive number of points when vehicle pitch up and down.  Pitching motions
 * will often not affect motion estimates from projected vectors on to the plane.
 *
 * @author Peter Abeles
 */
public class VisOdomMonoPlaneInfinity<T extends ImageBase> {

	// Motion estimator for points on plane.  Motion estimated is from key-frame to current-frame
	private ModelMatcher<Se2_F64, PlanePtPixel> planeMotion;
	// storage for data passed into planeMotion
	private FastQueue<PlanePtPixel> planeSamples = new FastQueue<>(PlanePtPixel.class, true);

	// when the inlier set is less than this number new features are detected
	private int thresholdAdd;
	// discard tracks after they have not been in the inlier set for this many updates in a row
	private int thresholdRetire;
	// maximum allowed pixel error.  Used for determining which tracks are inliers/outliers
	private double thresholdPixelError;

	// Should it use the more strict rule for pruning far away points?
	private boolean strictFar = false;

	// maximum allowed angle error for far pixels computed using pixel threshold and intrinsic parameters
	private double thresholdFarAngleError;

	// transform from the plane to the camera
	private Se3_F64 planeToCamera;
	private Se3_F64 cameraToPlane = new Se3_F64();

	// code for projection to/from plane
	private CameraPlaneProjection planeProjection = new CameraPlaneProjection();

	// tracks point features
	private PointTracker<T> tracker;

	// converts pixels between normalized image coordinates and pixel coordinates
	private Point2Transform2_F64 normToPixel;
	private Point2Transform2_F64 pixelToNorm;

	// tracks which are assumed to be at an infinite distance away
	private List<PointTrack> tracksFar = new ArrayList<>();
	// trans which lie on the ground plane
	private List<PointTrack> tracksOnPlane = new ArrayList<>();

	// storage for normalized image coordinate
	private Point2D_F64 n = new Point2D_F64();
	// storage for image pixel coordinate
	private Point2D_F64 pixel = new Point2D_F64();
	// 3D pointing vector of pixel observation
	private Vector3D_F64 pointing = new Vector3D_F64();
	// Adjusted pointing vector which removes off plane rotation
	private Vector3D_F64 pointingAdj = new Vector3D_F64();

	// pointing vector on ground in current frame
	private Point2D_F64 groundCurr = new Point2D_F64();

	// transform from key frame to world frame
	private Se2_F64 keyToWorld = new Se2_F64();
	// transform from the current camera view to the key frame
	private Se2_F64 currToKey = new Se2_F64();
	// transform from the current camera view to the world frame
	private Se2_F64 currToWorld = new Se2_F64();

	// storage for 3D transform
	private Se3_F64 currPlaneToWorld3D = new Se3_F64();
	private Se3_F64 worldToCurrPlane3D = new Se3_F64();
	private Se3_F64 worldToCurrCam3D = new Se3_F64();

	// local variable used in concating transforms
	private Se2_F64 temp = new Se2_F64();

	// angles of rotation computed from points far away
	private GrowQueue_F64 farAngles = new GrowQueue_F64();
	private GrowQueue_F64 farAnglesCopy = new GrowQueue_F64();

	// select angle from points far and the number of points used to estimate it
	private double farAngle;
	private int farInlierCount; // inlier count for points far away

	// found motion for close points.  From key-frame to current-frame
	private Se2_F64 closeMotionKeyToCurr;
	private int closeInlierCount; // inliers count for close points on plane

	// number of frames processed.  used to decide when tracks should get dropped
	private int tick = 0;
	// is this the first frame being processed?
	private boolean first = true;

	/**
	 * Configures motion estimation.
	 *
	 * @param thresholdAdd  New points are spawned when the number of on plane inliers drops below this value.
	 * @param thresholdRetire Tracks are dropped when they are not contained in the inlier set for this many frames
	 *                        in a row.  Try 2
	 * @param thresholdPixelError Threshold used to determine inliers.  Try 1.5
	 * @param planeMotion  Motion estimator for points on plane
	 * @param tracker Image feature tracker
	 */
	public VisOdomMonoPlaneInfinity(int thresholdAdd, int thresholdRetire, double thresholdPixelError,
									ModelMatcher<Se2_F64, PlanePtPixel> planeMotion, PointTracker<T> tracker) {
		this.thresholdAdd = thresholdAdd;
		this.thresholdRetire = thresholdRetire;
		this.thresholdPixelError = thresholdPixelError;
		this.planeMotion = planeMotion;
		this.tracker = tracker;
	}

	/**
	 * Camera the camera's intrinsic parameters.  Can be called at any time.
	 *
	 * @param intrinsic Intrinsic camera parameters
	 */
	public void setIntrinsic(CameraPinholeRadial intrinsic) {
		planeProjection.setIntrinsic(intrinsic);
		normToPixel = LensDistortionOps.transformPoint(intrinsic).distort_F64(false,true);
		pixelToNorm = LensDistortionOps.transformPoint(intrinsic).undistort_F64(true,false);

		// Find the change in angle caused by a pixel error in the image center.  The same angle error will induce a
		// larger change in pixel values towards the outside of the image edge.  For fish-eyes lenses this could
		// become significant.  Not sure what a better way to handle it would be
		thresholdFarAngleError = Math.atan2(thresholdPixelError, intrinsic.fx);
	}

	/**
	 * Camera the camera's extrinsic parameters.  Can be called at any time.
	 *
	 * @param planeToCamera Transform from the plane to camera.
	 */
	public void setExtrinsic(Se3_F64 planeToCamera) {
		this.planeToCamera = planeToCamera;
		planeToCamera.invert(cameraToPlane);

		planeProjection.setPlaneToCamera(planeToCamera, true);
	}

	/**
	 * Resets the algorithm into its initial state
	 */
	public void reset() {
		tick = 0;
		first = true;
		tracker.reset();
		keyToWorld.reset();
		currToKey.reset();
		currToWorld.reset();
		worldToCurrCam3D.reset();
	}

	/**
	 * Estimates the motion which the camera undergoes relative to the first frame processed.
	 *
	 * @param image Most recent camera image.
	 * @return true if motion was estimated or false if a fault occurred.  Should reset after a fault.
	 */
	public boolean process(T image) {
		// update feature tracks
		tracker.process(image);

		tick++;

		if (first) {
			// start motion estimation by spawning tracks and estimating their pose
			addNewTracks();
			first = false;
		} else {
			// Use updated tracks to update motion estimate and track states

			// use geometry to prune tracks and perform pre-processing for motion estimation
			sortTracksForEstimation();

			// estimate rotation using points at infinity
			estimateFar();

			// estimate rotation and translation using points on the plane
			if (!estimateClose()) {
				// if this fails it can't do anything
				return false;
			}

			// merge the two motion estimates together
			fuseEstimates();

			// discard tracks which aren't being used
			dropUnusedTracks();

			// see if new tracks need to be added
			if (thresholdAdd <= 0 || closeInlierCount < thresholdAdd) {
				changeCurrToReference();
				addNewTracks();
			}
		}

		return true;
	}

	/**
	 * Requests that new tracks are spawned, determines if they are on the plane or not, and computes other required
	 * data structures.
	 */
	private void addNewTracks() {
		tracker.spawnTracks();
		List<PointTrack> spawned = tracker.getNewTracks(null);

		// estimate 3D coordinate using stereo vision
		for (PointTrack t : spawned) {
//			System.out.println("track spawn "+t.x+" "+t.y);
			VoTrack p = t.getCookie();
			if (p == null) {
				t.cookie = p = new VoTrack();
			}

			// compute normalized image coordinate
			pixelToNorm.compute(t.x, t.y, n);

//			System.out.println("         pointing "+pointing.x+" "+pointing.y+" "+pointing.z);

			// See if the point ever intersects the ground plane or not
			if (planeProjection.normalToPlane(n.x, n.y, p.ground)) {
				// the line above computes the 2D plane position of the point
				p.onPlane = true;
			} else {
				// Handle points at infinity which are off plane.

				// rotate observation pointing vector into plane reference frame
				pointing.set(n.x, n.y, 1);
				GeometryMath_F64.mult(cameraToPlane.getR(), pointing, pointing);
				pointing.normalize();

				// save value of y-axis in pointing vector
				double normXZ = Math.sqrt(pointing.x * pointing.x + pointing.z * pointing.z);
				p.pointingY = pointing.y / normXZ;

				// save the angle as a vector
				p.ground.x = pointing.z;
				p.ground.y = -pointing.x;
				// normalize to make later calculations easier
				p.ground.x /= normXZ;
				p.ground.y /= normXZ;

				p.onPlane = false;
			}

			p.lastInlier = tick;
		}
	}


	/**
	 * Removes tracks which have not been included in the inlier set recently
	 *
	 * @return Number of dropped tracks
	 */
	private int dropUnusedTracks() {

		List<PointTrack> all = tracker.getAllTracks(null);
		int num = 0;

		for (PointTrack t : all) {
			VoTrack p = t.getCookie();
			if (tick - p.lastInlier > thresholdRetire) {
				tracker.dropTrack(t);
				num++;
			}
		}

		return num;
	}

	/**
	 * Splits the set of active tracks into on plane and infinity sets.  For each set also perform specific sanity
	 * checks to make sure basic constraints are still being meet.  If not then the track will not be considered for
	 * motion estimation.
	 */
	private void sortTracksForEstimation() {
		// reset data structures
		planeSamples.reset();
		farAngles.reset();
		tracksOnPlane.clear();
		tracksFar.clear();

		// list of active tracks
		List<PointTrack> active = tracker.getActiveTracks(null);

		for (PointTrack t : active) {
			VoTrack p = t.getCookie();

			// compute normalized image coordinate
			pixelToNorm.compute(t.x, t.y, n);

			// rotate pointing vector into plane reference frame
			pointing.set(n.x, n.y, 1);
			GeometryMath_F64.mult(cameraToPlane.getR(), pointing, pointing);
			pointing.normalize();

			if (p.onPlane) {
				// see if it still intersects the plane
				if (pointing.y > 0) {
					// create data structure for robust motion estimation
					PlanePtPixel ppp = planeSamples.grow();
					ppp.normalizedCurr.set(n);
					ppp.planeKey.set(p.ground);

					tracksOnPlane.add(t);
				}
			} else {
				// if the point is not on the plane visually and (optionally) if it passes a strict y-axis rotation
				// test, consider using the point for estimating rotation.
				boolean allGood = pointing.y < 0;
				if (strictFar) {
					allGood = isRotationFromAxisY(t, pointing);
				}
				// is it still above the ground plane and only has motion consistent with rotation on ground plane axis
				if (allGood) {
					computeAngleOfRotation(t, pointing);
					tracksFar.add(t);
				}
			}
		}
	}

	/**
	 * Checks for motion which can't be caused by rotations along the y-axis alone.  This is done by adjusting the
	 * pointing vector in the plane reference frame such that it has the same y component as when the track was spawned
	 * and that the x-z components are normalized to one, to ensure consistent units.  That pointing vector is then
	 * projected back into the image and a pixel difference computed.
	 *
	 * @param pointing Pointing vector of observation in plane reference frame
	 */
	protected boolean isRotationFromAxisY(PointTrack t, Vector3D_F64 pointing) {

		VoTrack p = t.getCookie();

		// remove rotations not along x-z plane
		double normXZ = Math.sqrt(pointing.x * pointing.x + pointing.z * pointing.z);
		pointingAdj.set(pointing.x / normXZ, p.pointingY, pointing.z / normXZ);
		// Put pointing vector back into camera frame
		GeometryMath_F64.multTran(cameraToPlane.getR(), pointingAdj, pointingAdj);

		// compute normalized image coordinates
		n.x = pointingAdj.x / pointingAdj.z;
		n.y = pointingAdj.y / pointingAdj.z;

		// compute pixel of projected point
		normToPixel.compute(n.x, n.y, pixel);

		// compute error
		double error = pixel.distance2(t);

		return error < thresholdPixelError * thresholdPixelError;
	}

	/**
	 * Computes the angle of rotation between two pointing vectors on the ground plane and adds it to a list.
	 *
	 * @param pointingPlane Pointing vector of observation in plane reference frame
	 */
	private void computeAngleOfRotation(PointTrack t, Vector3D_F64 pointingPlane) {
		VoTrack p = t.getCookie();

		// Compute ground pointing vector
		groundCurr.x = pointingPlane.z;
		groundCurr.y = -pointingPlane.x;
		double norm = groundCurr.norm();
		groundCurr.x /= norm;
		groundCurr.y /= norm;

		// dot product.  vectors are normalized to 1 already
		double dot = groundCurr.x * p.ground.x + groundCurr.y * p.ground.y;
		// floating point round off error some times knocks it above 1.0
		if (dot > 1.0)
			dot = 1.0;
		double angle = Math.acos(dot);

		// cross product to figure out direction
		if (groundCurr.x * p.ground.y - groundCurr.y * p.ground.x > 0)
			angle = -angle;

		farAngles.add(angle);
	}

	/**
	 * Estimates only rotation using points at infinity.  A robust estimation algorithm is used which finds an angle
	 * which maximizes the inlier set, like RANSAC does.  Unlike RANSAC this will produce an optimal result.
	 */
	private void estimateFar() {

		// do nothing if there are objects at infinity
		farInlierCount = 0;
		if (farAngles.size == 0)
			return;

		farAnglesCopy.reset();
		farAnglesCopy.addAll(farAngles);

		// find angle which maximizes inlier set
		farAngle = maximizeCountInSpread(farAnglesCopy.data, farAngles.size, 2 * thresholdFarAngleError);

		// mark and count inliers
		for (int i = 0; i < tracksFar.size(); i++) {
			PointTrack t = tracksFar.get(i);
			VoTrack p = t.getCookie();

			if (UtilAngle.dist(farAngles.get(i), farAngle) <= thresholdFarAngleError) {
				p.lastInlier = tick;
				farInlierCount++;
			}
		}
	}

	/**
	 * Estimates the full (x,y,yaw) 2D motion estimate from ground points.
	 *
	 * @return true if successful or false if not
	 */
	private boolean estimateClose() {

		// estimate 2D motion
		if (!planeMotion.process(planeSamples.toList()))
			return false;

		// save solutions
		closeMotionKeyToCurr = planeMotion.getModelParameters();
		closeInlierCount = planeMotion.getMatchSet().size();

		// mark inliers as used
		for (int i = 0; i < closeInlierCount; i++) {
			int index = planeMotion.getInputIndex(i);
			VoTrack p = tracksOnPlane.get(index).getCookie();
			p.lastInlier = tick;
		}

		return true;
	}

	/**
	 * Fuse the estimates for yaw from both sets of points using a weighted vector average and save the results
	 * into currToKey
	 */
	private void fuseEstimates() {

		// weighted average for angle
		double x = closeMotionKeyToCurr.c * closeInlierCount + Math.cos(farAngle) * farInlierCount;
		double y = closeMotionKeyToCurr.s * closeInlierCount + Math.sin(farAngle) * farInlierCount;

		// update the motion estimate
		closeMotionKeyToCurr.setYaw(Math.atan2(y, x));

		// save the results
		closeMotionKeyToCurr.invert(currToKey);
	}

	/**
	 * Updates the relative position of all points so that the current frame is the reference frame.  Mathematically
	 * this is not needed, but should help keep numbers from getting too large.
	 */
	private void changeCurrToReference() {
		Se2_F64 keyToCurr = currToKey.invert(null);

		List<PointTrack> all = tracker.getAllTracks(null);

		for (PointTrack t : all) {
			VoTrack p = t.getCookie();

			if (p.onPlane) {
				SePointOps_F64.transform(keyToCurr, p.ground, p.ground);
			} else {
				GeometryMath_F64.rotate(keyToCurr.c, keyToCurr.s, p.ground, p.ground);
			}
		}

		concatMotion();
	}

	/**
	 * Number of frames processed
	 * @return Number of frames processed
	 */
	public int getTick() {
		return tick;
	}

	/**
	 * Add motion estimate from key frame into the estimate from world
	 */
	private void concatMotion() {
		currToKey.concat(keyToWorld, temp);
		keyToWorld.set(temp);
		currToKey.reset();
	}

	/**
	 * Returns the 2D motion estimate
	 * @return Motion estimate from current frame into world frame in 2D
	 */
	public Se2_F64 getCurrToWorld2D() {
		currToKey.concat(keyToWorld, currToWorld);
		return currToWorld;
	}

	/**
	 * Converts 2D motion estimate into a 3D motion estimate
	 *
	 * @return from world to current frame.
	 */
	public Se3_F64 getWorldToCurr3D() {

		// compute transform in 2D space
		Se2_F64 currToWorld = getCurrToWorld2D();

		// 2D to 3D coordinates
		currPlaneToWorld3D.getT().set(-currToWorld.T.y, 0, currToWorld.T.x);
		DenseMatrix64F R = currPlaneToWorld3D.getR();

		// set rotation around Y axis.
		// Transpose the 2D transform since the rotation are pointing in opposite directions
		R.unsafe_set(0, 0, currToWorld.c);
		R.unsafe_set(0, 2, -currToWorld.s);
		R.unsafe_set(1, 1, 1);
		R.unsafe_set(2, 0, currToWorld.s);
		R.unsafe_set(2, 2, currToWorld.c);

		currPlaneToWorld3D.invert(worldToCurrPlane3D);

		worldToCurrPlane3D.concat(planeToCamera, worldToCurrCam3D);

		return worldToCurrCam3D;
	}


	/**
	 * Finds the value which has the largest number of points above and below it within the specified spread
	 *
	 * @param data      Input data.  Is modified by sort
	 * @param size      number of elements in data
	 * @param maxSpread the spread it's going after
	 * @return best value
	 */
	public static double maximizeCountInSpread(double[] data, int size, double maxSpread) {
		if (size <= 0)
			return 0;

		Arrays.sort(data, 0, size);

		int length = 0;
		for (; length < size; length++) {
			double s = UtilAngle.dist(data[0], data[length]);
			if (s > maxSpread) {
				break;
			}
		}

		int bestStart = 0;
		int bestLength = length;

		int start;
		for (start = 1; start < size && length < size; start++) {
			length--;

			while (length < size) {
				double s = UtilAngle.dist(data[start], data[(start + length) % size]);
				if (s > maxSpread) {
					break;
				} else {
					length++;
				}
			}
			if (length > bestLength) {
				bestLength = length;
				bestStart = start;
			}
		}

		return data[(bestStart + bestLength / 2) % size];
	}

	public PointTracker<T> getTracker() {
		return tracker;
	}

	public boolean isStrictFar() {
		return strictFar;
	}

	public void setStrictFar(boolean strictFar) {
		this.strictFar = strictFar;
	}

	/**
	 * Additional track information for use in motion estimation
	 */
	public static class VoTrack {
		// Y-axis of pointing vector in key-frame of plane reference frame.  x-z components are normalized to one
		// to ensure a constant scale is used
		double pointingY;

		// ----- Observations in key-frame
		// 2D location or angle vector on ground plane for point on ground and at infinity, respectively
		public Point2D_F64 ground = new Point2D_F64();

		// the tick in which it was last an inlier
		public long lastInlier;

		// true for point on plane and false for infinity
		public boolean onPlane;
	}
}

package com.company;

import com.github.davidmoten.rtree2.Entry;
import com.github.davidmoten.rtree2.Iterables;
import com.github.davidmoten.rtree2.RTree;
import com.github.davidmoten.rtree2.geometry.Geometries;
import com.github.davidmoten.rtree2.geometry.Geometry;
import com.github.davidmoten.rtree2.geometry.internal.PointDouble;
import com.github.mreutegg.laszip4j.LASHeader;
import com.github.mreutegg.laszip4j.LASPoint;
import com.github.mreutegg.laszip4j.LASReader;
import com.google.common.escape.ArrayBasedCharEscaper;
import com.sun.media.jai.rmi.VectorState;
import org.ejml.dense.block.VectorOps_DDRB;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.geometry.Envelope2D;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.GeodeticCalculator;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.math.Vector3D;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.opengis.feature.Feature;
import org.opengis.feature.GeometryAttribute;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import javax.vecmath.Vector3d;
import java.io.*;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Main {

    private static final String SHAPES_FILE = "../GJI_SLO_SHP_G_1100/GJI_SLO_1100_ILL_20200402.shp";

    private static LASReader reader;

    private static RTree<Vector3d, Geometry> terrainTree;

    private static CoordinateReferenceSystem LAZ_CS, SHP_CS;

    private static Random random;

    private static double scaleFactorX, scaleFactorY;


    public static void main(String[] args) throws FactoryException, IOException, TransformException {


        // Time needed for Delaunay triangulation of file GK_430_136.laz which contained 12M points was 40 minutes on Intel i7 6700K.
        reader = new LASReader(new File("GK_468_104.laz"));

        LASHeader lasHeader = reader.getHeader();
        scaleFactorX = lasHeader.getXScaleFactor();
        scaleFactorY = lasHeader.getYScaleFactor();


        terrainTree = RTree.create();

        random = new Random();

        LAZ_CS = CRS.decode("EPSG:3787");

        double left = lasHeader.getMinX();
        double right = lasHeader.getMaxX();
        double top = lasHeader.getMinY();
        double bottom = lasHeader.getMaxY();

        Envelope2D bounds = new Envelope2D();
        bounds.x = left;
        bounds.y = top;
        bounds.width = right - left;
        bounds.height = bottom - top;//left, right, top, bottom, lasHeader.getMinZ(), lasHeader.getMaxZ(), new DefaultDerivedCRS());
        bounds.setCoordinateReferenceSystem(LAZ_CS);


        ArrayList<Bridge> bridges = readShapeFile(new File(SHAPES_FILE), bounds);

        // TODO: Add whatever beneath the bridge. Check which types. Probaby water/river under bridge, road etc.


        System.out.println("bridges = " + bridges.size());

        while(!mergeBridges(bridges)) {
            System.out.println("Repeat...");
        }
        System.out.println("bridges = " + bridges.size());
        MathTransform transform = CRS.findMathTransform(SHP_CS, LAZ_CS, false);

        int count = 0;
        for (Bridge bridge : bridges) {
            BoundingBox bridgeBounds = bridge.getSkirt();
            System.out.println("bridgeBounds = " + bridgeBounds.toString());
            LASReader subread = reader.insideRectangle(bridgeBounds.getMinX(), bridgeBounds.getMinY(), bridgeBounds.getMaxX(), bridgeBounds.getMaxY());

            ArrayList<Vector3d> points = new ArrayList<>();
            for (LASPoint point : subread.getPoints()) {
                points.add(new Vector3d(point.getX(), point.getY(), point.getZ()));
            }


            // TODO: Get bridge width. Maybe even use meters.
            double BRIDGE_WIDTH = 12;

            ArrayList<Vector3d> bridgePoints = new ArrayList<>();
            for (Vector3d vector3D : points) {
                /*if (vector3D.getZ() > minBridgeZ && vector3D.getZ() < maxBridgeZ) {
                    bridgePoints.add(vector3D);
                }*/
                Point point = new GeometryFactory().createPoint(new Coordinate(vector3D.x * lasHeader.getXScaleFactor(), vector3D.y * lasHeader.getYScaleFactor()));


                boolean isTypeBridge = false;
                for(MultiLineString bridgeComponent : bridge.getBridges()) {
                    MultiLineString targetBridge = (MultiLineString) JTS.transform(bridgeComponent, transform);

                    /*
                    GeodeticCalculator gc = new GeodeticCalculator(LAZ_CS);
                    gc.setStartingPosition(JTS.toDirectPosition(DistanceOp.closestPoints(targetBridge, point)[0], LAZ_CS));
                    gc.setDestinationPosition(JTS.toDirectPosition( point.getCoordinate(), LAZ_CS));
                    System.out.println("start = " + gc.getStartingPosition().toString());
                    System.out.println("end = " + gc.getDestinationPosition().toString());
                    double distance = gc.getOrthodromicDistance();
                    */

                    Coordinate coordinate1 = DistanceOp.closestPoints(targetBridge, point)[0];
                    Coordinate coordinate2 = point.getCoordinate();

                    double distance = coordinate1.distance(coordinate2);

                    if (distance < BRIDGE_WIDTH) {
                        isTypeBridge = true;
                        break;
                    }
                }

                if(isTypeBridge) {
                    bridgePoints.add(vector3D);
                }
                else {
                    terrainTree = terrainTree.add(vector3D, PointDouble.create(vector3D.x, vector3D.y));
                }
            }

            ArrayList<Vector3d> generatedPoints = new ArrayList<>();
            // TODO: Instead of this calculate a vector parallel to river, road and denstity at the end of bridge.
            // Then in steps go through and create points.
            for(MultiLineString bridgeComponent : bridge.getBridges()) {
                System.out.println("geometries = " + bridgeComponent.getNumGeometries());
                for (int i = 0; i < bridgeComponent.getNumGeometries(); i++) {
                    LineString partOfBridge = (LineString) JTS.transform(bridgeComponent.getGeometryN(i), transform);

                    Point A = partOfBridge.getStartPoint();
                    Point B = partOfBridge.getEndPoint();
                    Vector3d directionVector = new Vector3d(B.getX() - A.getX(), B.getY() - A.getY(), 0);
                    double bridgeLength = directionVector.length();
                    directionVector.normalize();
                    Vector3d normal = new Vector3d();
                    normal.cross(directionVector, new Vector3d(0, 0, 1));
                    normal.normalize();

                    // TODO: Get RealVector, realDistance
                    /*Vector3D realVector = realVector.normalize();*/
                    Vector3d realVector = normal;
                    double realDistance = BRIDGE_WIDTH / 2;

                    int BRIDGE_STEP = 1;

                    System.out.println("bridge length = " + bridgeLength);
                    for (int j = 0; j < bridgeLength; j += BRIDGE_STEP) {
                        System.out.println("done = " + (j / bridgeLength));
                        Vector3d bridgePoint = new Vector3d(directionVector);

                        bridgePoint.scale(j);
                        bridgePoint.add(new Vector3d(A.getX(), A.getY(), 0));
                        System.out.println("bridgePoint = " + bridgePoint.toString());


                        /*MultiLineString targetBridge = (MultiLineString) JTS.transform(bridgeComponent, transform);
                        Point point = new GeometryFactory().createPoint(new Coordinate(bridgePoint.x * lasHeader.getXScaleFactor(), bridgePoint.y * lasHeader.getYScaleFactor()));
                        Coordinate coordinate1 = DistanceOp.closestPoints(targetBridge, point)[0];
                        bridgePoint.x = coordinate1.x;
                        bridgePoint.y = coordinate1.y;*/

                        //generatedPoints.add(new Vector3d(bridgePoint.x, bridgePoint.y, 0));

                        //Vector3d tempNormal = new Vector3d(normal);
                        //tempNormal.scale(realDistance * 1.2f);
                        //bridgePoint.add(tempNormal);

                        System.out.println("bridgePoint = " + bridgePoint.toString());

                        // FIXME: STEP. TK
                        double STEP = 1;//density(bridgePoint, 10) * 10;
                        System.out.println("STEP = " + STEP);

                        System.out.println(lasHeader.getXScaleFactor());

                        for(double k = realDistance * 2; Math.abs(k) > 0; k -= STEP) {
                            traverseBridge(generatedPoints, bridgePoint, realVector, k);
                            traverseBridge(generatedPoints, bridgePoint, realVector, -k);
                        }

                        System.out.println(bridgeBounds.contains(bridgePoint.x, bridgePoint.y));

                        bridgePoint.x = bridgePoint.x * (1/scaleFactorX);
                        bridgePoint.y = bridgePoint.y * (1/scaleFactorY);
                        generatedPoints.add(new Vector3d(bridgePoint.x, bridgePoint.y, interpolateZ(bridgePoint)));
                    }
                }
            }

            points.addAll(generatedPoints);
            System.out.println("points = " + points.size());
            System.out.println("bridge points = " + bridgePoints.size());
            points.removeAll(bridgePoints);

            write(points, count++);
            //write(generatedPoints, count++);
            System.exit(0);
        }
    }

    private static void traverseBridge(ArrayList<Vector3d> generatedPoints,
                                Vector3d pointOnBridge,
                                Vector3d direction,
                                double k) {
        Vector3d bridgePoint = new Vector3d(pointOnBridge);

        Vector3d tempDirection = new Vector3d(direction);
        tempDirection.scale(k);
        bridgePoint.add(tempDirection);

        bridgePoint.x = bridgePoint.x * (1/scaleFactorX);
        bridgePoint.y = bridgePoint.y * (1/scaleFactorY);

        /*if(density(bridgePoint, 10) > 1)
            break;*/
        if(density(bridgePoint, generatedPoints, 20) > 0)
            return;

        Vector3d result = new Vector3d(bridgePoint.x, bridgePoint.y, interpolateZ(bridgePoint));
        generatedPoints.add(result);
        //System.out.println("result = " + result.toString());
        //terrainTree = terrainTree.add(result, PointDouble.create(result.x, result.y));
    }

    private static int density(Vector3d point, ArrayList<Vector3d> bridgePoints, int radius) {
        int count = 0;
        for(Vector3d bridgePoint : bridgePoints) {
            if(distance(bridgePoint, point) < radius)
                count++;
        }
        return count;
    }

    /*private static int density(Vector3d point, int radius) {
        Iterable<Entry<Vector3d, Geometry>> iterable = terrainTree.nearest(Geometries.point(point.x, point.y), radius, 10);
        int count = 0;
        while(iterable.iterator().hasNext()) {
            iterable.iterator().next();
            count++;
        }
        return count;
    }*/

    // Merge overlapping bridges
    private static boolean mergeBridges(ArrayList<Bridge> bridges) {
        for(int i = 0; i < bridges.size(); i++) {
            for(int j = i + 1; j < bridges.size(); j++) {
                Bridge bridge1 = bridges.get(i);
                Bridge bridge2 = bridges.get(j);
                if(bridge1.getSkirt().intersects(bridge2.getSkirt())) {
                    bridge1.getSkirt().include(bridge2.getSkirt());
                    bridge1.addBridges(bridge2.getBridges());
                    bridges.remove(bridge2);
                    return false;
                }
            }
        }
        return true;
    }

    private static double interpolateZ(Vector3d bridgePoint) {
        List<Entry<Vector3d, Geometry>> entries = Iterables.toList(terrainTree.nearest(Geometries.point(bridgePoint.x, bridgePoint.y), Double.MAX_VALUE, 40));
        double values = 0;
        double weightsSum = 0;

        // Sort by Z value.
        Collections.sort(entries, new Comparator<Entry<Vector3d, Geometry>>() {
            @Override
            public int compare(Entry<Vector3d, Geometry> o1, Entry<Vector3d, Geometry> o2) {
                /*System.out.println(o1.value().z);
                System.out.println(o2.value().z);*/
                if(o1.value().z < o2.value().z) {
                    return -1;
                }
                else if(o1.value().z > o2.value().z) {
                    return 1;
                }
                else
                    return 0;
            }
        });
        entries = entries.subList(0, 10);

        //System.out.println("size = " + entries.size());
        for (Entry<Vector3d, Geometry> entry : entries) {
            double distance = distance(entry.value(), bridgePoint);
            if(distance == 0)
                continue;

            double newWeight = 1 / distance;

            values += newWeight * entry.value().z;
            weightsSum += newWeight;
        }
        //System.out.println("values = " + values);
        //System.out.println("weightsum = " + weightsSum);

        double z =  values / weightsSum;
        //System.out.println("Z = " + z);
        return z;
    }

    private static double distance(Vector3d a, Vector3d b) {
        double distance = Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2));
        //System.out.println("distance = " + distance);
        return distance;
    }

    private static void write(ArrayList<Vector3d> points, int number) {
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(String.format("test%d.obj", number)), StandardCharsets.UTF_8))) {
            for(Vector3d lasPoint : points) {
                writer.write("v " + lasPoint.x + " " + lasPoint.y + " " + lasPoint.z + "\n");
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static ArrayList<Bridge> readShapeFile(File file, Envelope2D bounds) {
        ArrayList<Bridge> result = new ArrayList<>();

        try {
            Map<String, String> connect = new HashMap();
            connect.put("url", file.toURI().toString());

            DataStore dataStore = DataStoreFinder.getDataStore(connect);
            String[] typeNames = dataStore.getTypeNames();
            String typeName = typeNames[0];

            System.out.println("Reading content " + typeName);

            FeatureSource featureSource = dataStore.getFeatureSource(typeName);

            // Only get the bridges.
            String filterStatement = "\"SIF_VRSTE\" = 1102 AND \"ATR2\" = 1";
            //String filterStatement = "\"SIF_VRSTE\" = 1102 AND \"ATR2\" = 1 AND ID = 17530217";

            FeatureCollection collection = featureSource.getFeatures().subCollection(CQL.toFilter(filterStatement));
            FeatureIterator iterator = collection.features();

            try {
                while (iterator.hasNext()) {
                    Feature feature = iterator.next();
                    GeometryAttribute sourceGeometry = feature.getDefaultGeometryProperty();

                    if(SHP_CS == null) {
                        SHP_CS = sourceGeometry.getBounds().getCoordinateReferenceSystem();
                    }
                    //if(bounds.intersects(sourceGeometry.getBounds()))

                    //System.out.println("type = " + sourceGeometry.getBounds().toString());
                    //System.out.println("bbox = " + bounds.toString());


                    CoordinateReferenceSystem coordinateReferenceSystem = sourceGeometry.getBounds().getCoordinateReferenceSystem();
                    BoundingBox bridgeBox = sourceGeometry.getBounds();
                    BoundingBox lasBox = bounds.toBounds(coordinateReferenceSystem);


                    if(bridgeBox.intersects(lasBox)) {
                        System.out.println("FOUND!");
                        Bridge bridge = new Bridge();
                        if(sourceGeometry.getValue() instanceof MultiLineString) {
                            MultiLineString multiLineString = (MultiLineString) sourceGeometry.getValue();
                            bridge.addBridge(multiLineString);
                        }

                        bridge.setSkirt(bridgeBox.toBounds(LAZ_CS));
                        result.add(bridge);
                    }
                }
            } finally {
                iterator.close();
            }


        }
        catch (Throwable e) {
            e.printStackTrace();
        }
        System.out.println("length = " + result.size());
        return result;
    }


}

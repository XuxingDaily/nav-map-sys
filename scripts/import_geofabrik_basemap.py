"""Import the selected Guangzhou Geofabrik basemap themes into PostgreSQL.

Requires GDAL's Python bindings with the PostgreSQL driver enabled. The import
is transactional and replaces only the Geofabrik basemap rows and POIs that it
owns; the road network and existing POI sources are left untouched.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import uuid

from osgeo import ogr, osr


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_GPKG = (
    PROJECT_ROOT
    / "data/raw/guangdong-261025-free-geofabrik.gpkg/guangdong.gpkg"
)
DEFAULT_AREA = (
    PROJECT_ROOT
    / "data/raw/guangdong-261025-free-geofabrik.gpkg/guangzhou_area.json"
)
POI_NAMESPACE = uuid.UUID("ce011ec7-f437-5a95-b642-8d5e4ad9fdb9")


def load_env() -> dict[str, str]:
    result: dict[str, str] = {}
    for path in (PROJECT_ROOT / ".env", PROJECT_ROOT.parent / ".env"):
        if not path.is_file():
            continue
        for raw_line in path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            result.setdefault(key.strip(), value.strip().strip("\"'"))
    result.update({key: value for key, value in os.environ.items() if value})
    return result


def postgres_connection_string(config: dict[str, str]) -> str:
    jdbc_url = config.get("DATABASE_URL", "")
    match = re.fullmatch(
        r"jdbc:postgresql://(?P<host>[^:/?]+)(?::(?P<port>\d+))?/(?P<database>[^?]+)(?:\?.*)?",
        jdbc_url,
    )
    if not match:
        raise ValueError("DATABASE_URL must be a PostgreSQL JDBC URL")

    def quote(value: str) -> str:
        return value.replace("\\", "\\\\").replace("'", "\\'")

    values = match.groupdict()
    username = config.get("DATABASE_USERNAME")
    password = config.get("DATABASE_PASSWORD")
    if not username or password is None:
        raise ValueError("DATABASE_USERNAME and DATABASE_PASSWORD are required")
    return (
        "PG:"
        f"host='{quote(values['host'])}' "
        f"port='{quote(values['port'] or '5432')}' "
        f"dbname='{quote(values['database'])}' "
        f"user='{quote(username)}' "
        f"password='{quote(password)}'"
    )


def spatial_reference(epsg: int) -> osr.SpatialReference:
    reference = osr.SpatialReference()
    reference.ImportFromEPSG(epsg)
    reference.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    return reference


WGS84 = spatial_reference(4326)
WEB_MERCATOR = spatial_reference(3857)
TO_WEB_MERCATOR = osr.CoordinateTransformation(WGS84, WEB_MERCATOR)


def make_valid_polygonal(geometry: ogr.Geometry | None) -> ogr.Geometry | None:
    if geometry is None or geometry.IsEmpty():
        return None
    valid = geometry.MakeValid()
    flat_type = ogr.GT_Flatten(valid.GetGeometryType())
    if flat_type == ogr.wkbPolygon:
        result = ogr.ForceToMultiPolygon(valid)
    elif flat_type == ogr.wkbMultiPolygon:
        result = valid
    elif flat_type == ogr.wkbGeometryCollection:
        result = ogr.Geometry(ogr.wkbMultiPolygon)
        for index in range(valid.GetGeometryCount()):
            part = valid.GetGeometryRef(index)
            part_type = ogr.GT_Flatten(part.GetGeometryType())
            if part_type == ogr.wkbPolygon:
                result.AddGeometry(part)
            elif part_type == ogr.wkbMultiPolygon:
                for child_index in range(part.GetGeometryCount()):
                    result.AddGeometry(part.GetGeometryRef(child_index))
    else:
        return None
    if result.IsEmpty():
        return None
    result.AssignSpatialReference(WGS84)
    return result


def load_guangzhou_area(area_path: Path) -> tuple[ogr.DataSource, ogr.Geometry]:
    dataset = ogr.Open(str(area_path))
    if dataset is None:
        raise RuntimeError(f"Cannot open Guangzhou boundary: {area_path}")
    layer = dataset.GetLayer(0)
    geometries = [feature.GetGeometryRef().Clone() for feature in layer]
    if not geometries:
        raise RuntimeError("Guangzhou boundary contains no features")
    union = geometries[0]
    for geometry in geometries[1:]:
        union = union.Union(geometry)
    polygonal = make_valid_polygonal(union)
    if polygonal is None:
        raise RuntimeError("Guangzhou boundary did not produce a valid polygon")
    layer.ResetReading()
    return dataset, polygonal


def projected_area(geometry: ogr.Geometry) -> float:
    projected = geometry.Clone()
    projected.Transform(TO_WEB_MERCATOR)
    return projected.GetArea()


def surface_min_zoom(theme: str, feature_class: str, area_m2: float) -> int:
    if theme == "building":
        if area_m2 >= 50_000:
            return 11
        if area_m2 >= 10_000:
            return 12
        if area_m2 >= 1_000:
            return 13
        return 14
    if theme == "parking":
        if area_m2 >= 50_000:
            return 13
        if area_m2 >= 10_000:
            return 14
        return 15

    if area_m2 >= 10_000_000:
        area_zoom = 3 if theme == "landcover" else 2
    elif area_m2 >= 1_000_000:
        area_zoom = 4
    elif area_m2 >= 100_000:
        area_zoom = 6
    elif area_m2 >= 10_000:
        area_zoom = 8
    else:
        area_zoom = 10

    if theme == "water":
        return area_zoom

    class_floor = {
        "forest": 3,
        "residential": 3,
        "industrial": 4,
        "park": 5,
        "commercial": 5,
        "retail": 5,
        "military": 5,
        "grass": 6,
        "farmland": 6,
        "meadow": 6,
        "orchard": 6,
        "scrub": 6,
        "recreation_ground": 6,
    }.get(feature_class, 8)
    return max(class_floor, area_zoom)


def set_optional_field(feature: ogr.Feature, field: str, value: object | None) -> None:
    if value is not None and value != "":
        feature.SetField(field, value)


def checked_create(layer: ogr.Layer, feature: ogr.Feature) -> None:
    error = layer.CreateFeature(feature)
    if error != ogr.OGRERR_NONE:
        raise RuntimeError(f"Failed writing feature to {layer.GetName()}: OGR error {error}")


def execute_sql(dataset: ogr.DataSource, sql: str) -> None:
    result = dataset.ExecuteSQL(sql)
    if result is not None:
        dataset.ReleaseResultSet(result)


def verify_target(dataset: ogr.DataSource) -> None:
    result = dataset.ExecuteSQL(
        "SELECT EXISTS (SELECT 1 FROM public.flyway_schema_history "
        "WHERE version = '19' AND success)"
    )
    try:
        feature = result.GetNextFeature() if result is not None else None
        if feature is None or not feature.GetField(0):
            raise RuntimeError("Target database must be migrated through Flyway V19")
    finally:
        if result is not None:
            dataset.ReleaseResultSet(result)


def import_admin_areas(
    area_dataset: ogr.DataSource,
    target_layer: ogr.Layer,
) -> int:
    source_layer = area_dataset.GetLayer(0)
    source_layer.ResetReading()
    count = 0
    for source in source_layer:
        geometry = make_valid_polygonal(source.GetGeometryRef())
        if geometry is None:
            continue
        target = ogr.Feature(target_layer.GetLayerDefn())
        target.SetFID(int(source.GetField("adcode")))
        target.SetField("name", source.GetField("name"))
        target.SetField("level", source.GetField("level") or "district")
        parent_adcode = None
        parent = source.GetField("parent")
        if parent:
            try:
                parent_adcode = json.loads(parent).get("adcode")
            except (TypeError, ValueError, json.JSONDecodeError):
                parent_adcode = None
        set_optional_field(target, "parent_adcode", parent_adcode)
        properties = {
            source.GetFieldDefnRef(index).GetName(): source.GetField(index)
            for index in range(source.GetFieldCount())
        }
        target.SetField("properties", json.dumps(properties, ensure_ascii=False))
        target.SetGeometry(geometry)
        checked_create(target_layer, target)
        count += 1
    return count


def import_surfaces(
    source_dataset: ogr.DataSource,
    target_layer: ogr.Layer,
    clip_geometry: ogr.Geometry,
) -> dict[str, int]:
    mappings = (
        ("gis_osm_landuse_a_free", "landcover", None),
        ("gis_osm_water_a_free", "water", None),
        ("gis_osm_buildings_a_free", "building", None),
        ("gis_osm_traffic_a_free", "parking", {"parking"}),
    )
    counts: dict[str, int] = {}
    for source_name, theme, allowed_classes in mappings:
        source_layer = source_dataset.GetLayerByName(source_name)
        source_layer.SetSpatialFilter(clip_geometry)
        count = 0
        for source in source_layer:
            feature_class = source.GetField("fclass") or "unknown"
            if allowed_classes is not None and feature_class not in allowed_classes:
                continue
            clipped = source.GetGeometryRef().Intersection(clip_geometry)
            geometry = make_valid_polygonal(clipped)
            if geometry is None:
                continue
            target = ogr.Feature(target_layer.GetLayerDefn())
            target.SetField("theme", theme)
            target.SetField("source_layer", source_name)
            set_optional_field(target, "source_feature_id", source.GetField("osm_id"))
            target.SetField("class", feature_class)
            set_optional_field(target, "code", source.GetField("code"))
            set_optional_field(target, "name", source.GetField("name"))
            target.SetField("min_zoom", surface_min_zoom(theme, feature_class, projected_area(geometry)))
            target.SetField("max_zoom", 22)
            target.SetGeometry(geometry)
            checked_create(target_layer, target)
            count += 1
        source_layer.SetSpatialFilter(None)
        counts[theme] = count
        print(f"Imported {count:,} {theme} polygons")
    return counts


def import_area_pois(
    source_dataset: ogr.DataSource,
    target_layer: ogr.Layer,
    clip_geometry: ogr.Geometry,
) -> int:
    mappings = (
        ("gis_osm_pois_a_free", "geofabrik_poi", None),
        ("gis_osm_pofw_a_free", "place_of_worship", None),
        ("gis_osm_transport_a_free", "transport", None),
        ("gis_osm_traffic_a_free", "traffic", {"parking"}),
    )
    count = 0
    for source_name, basic_category, excluded_classes in mappings:
        source_layer = source_dataset.GetLayerByName(source_name)
        source_layer.SetSpatialFilter(clip_geometry)
        for source in source_layer:
            name = source.GetField("name")
            feature_class = source.GetField("fclass") or "unknown"
            if not name or (excluded_classes and feature_class in excluded_classes):
                continue
            clipped = source.GetGeometryRef().Intersection(clip_geometry)
            geometry = make_valid_polygonal(clipped)
            if geometry is None:
                continue
            point = geometry.PointOnSurface()
            point.AssignSpatialReference(WGS84)
            source_key = (
                f"{source_name}:{source.GetField('osm_id') or 'none'}:"
                f"{feature_class}:{source.GetFID()}"
            )
            target = ogr.Feature(target_layer.GetLayerDefn())
            target.SetField("source_id", str(uuid.uuid5(POI_NAMESPACE, source_key)))
            target.SetField("source_provider", "geofabrik_area")
            target.SetField("source_ref", source_key)
            target.SetField("name", name)
            target.SetField("context", f"Geofabrik area · {feature_class}")
            target.SetField("category", feature_class)
            target.SetField("basic_category", basic_category)
            target.SetField("coordinate_status", "projected_3857")
            target.SetField("status", "active")
            target.SetField("data_version", 1)
            target.SetGeometry(point)
            checked_create(target_layer, target)
            count += 1
        source_layer.SetSpatialFilter(None)
    print(f"Imported {count:,} named area POIs")
    return count


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gpkg", type=Path, default=DEFAULT_GPKG)
    parser.add_argument("--area", type=Path, default=DEFAULT_AREA)
    parser.add_argument(
        "--replace",
        action="store_true",
        help="acknowledge replacement of the imported basemap and Geofabrik-owned POIs",
    )
    args = parser.parse_args()
    if not args.replace:
        parser.error("--replace is required")

    source_dataset = ogr.Open(str(args.gpkg))
    if source_dataset is None:
        raise RuntimeError(f"Cannot open GeoPackage: {args.gpkg}")
    area_dataset, guangzhou_area = load_guangzhou_area(args.area)

    target_dataset = ogr.Open(postgres_connection_string(load_env()), update=1)
    if target_dataset is None:
        raise RuntimeError("Cannot connect to the target PostgreSQL database")
    verify_target(target_dataset)

    if target_dataset.StartTransaction() != ogr.OGRERR_NONE:
        raise RuntimeError("PostgreSQL driver did not start a transaction")
    try:
        execute_sql(target_dataset, "TRUNCATE public.base_map_lod, public.base_surface_feature RESTART IDENTITY")
        execute_sql(target_dataset, "DELETE FROM public.admin_area")
        execute_sql(target_dataset, "DELETE FROM public.poi WHERE source_provider = 'geofabrik_area'")

        admin_count = import_admin_areas(
            area_dataset,
            target_dataset.GetLayerByName("admin_area"),
        )
        surface_counts = import_surfaces(
            source_dataset,
            target_dataset.GetLayerByName("base_surface_feature"),
            guangzhou_area,
        )
        poi_count = import_area_pois(
            source_dataset,
            target_dataset.GetLayerByName("poi"),
            guangzhou_area,
        )

        print("Refreshing shared basemap LOD...")
        execute_sql(target_dataset, "SELECT public.refresh_base_map_lod()")
        execute_sql(target_dataset, "SELECT public.bump_dataset_revision('map')")
        execute_sql(target_dataset, "SELECT public.bump_dataset_revision('poi')")
        execute_sql(target_dataset, "ANALYZE public.base_surface_feature")
        execute_sql(target_dataset, "ANALYZE public.admin_area")
        execute_sql(target_dataset, "ANALYZE public.poi")

        if target_dataset.CommitTransaction() != ogr.OGRERR_NONE:
            raise RuntimeError("PostgreSQL transaction commit failed")
    except Exception:
        target_dataset.RollbackTransaction()
        raise

    print(
        "Import committed: "
        f"{admin_count:,} districts, "
        + ", ".join(f"{count:,} {theme}" for theme, count in surface_counts.items())
        + f", {poi_count:,} area POIs"
    )


if __name__ == "__main__":
    main()

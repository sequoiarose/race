import * as config from "./config.js";
import * as ws from "./ws.js";
import { SkipList, CircularBuffer } from "./ui_data.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

const NO_PATH = "";
const LINE_PATH = "~";
const WALL_PATH = "≈";

class TrackAssets {
    constructor(symbol, info = null) {
        this.symbol = symbol;
        this.info = info;

        // on demand
        this.trajectoryPositions = []; // the value we use in the CallbackProperty for trajectory.positions (to avoid flicker) 
        this.trajectory = null;
    }
}

// object that wraps server-supplied track info with our locally kept trace and display assets
class TrackEntry {
    constructor(track, assets, trackSource) {
        this.track = track;
        this.assets = assets;
        this.trackSource = trackSource;

        this.id = track.label;
        this.trace = new CircularBuffer(config.maxTraceLength);
    }

    assetDisplay() {
        let s = "";
        if (this.assets && this.assets.trajectory) {
            let tr = this.assets.trajectory;
            if (tr.polyline) s += LINE_PATH;
            else if (tr.wall) s += WALL_PATH;
            else s += NO_PATH;
        }
        return s;
    }
}

class TrackSource {
    constructor(id) {
        this.id = id;

        this.show = true;
        this.trackEntries = new Map();
        this.date = 0; // last change of trackEntries
        this.dataSource = new Cesium.CustomDataSource(id); // display list for Cesium track entities (don't forget to add to viewer)

        // we keep those in different data sources so that we can control Z-order and 
        // bulk enable/disable display more efficiently
        this.trackInfoDataSource = new Cesium.CustomDataSource(id + '-trackInfo');
        this.trajectoryDataSource = new Cesium.CustomDataSource(id + '-trajectories');

        this.entityPrototype = null;

        this.trackEntryList = new SkipList( // id-sorted display list for trackEntryView
            5, // max depth
            (a, b) => a.id < b.id, // sort function
            (a, b) => a.id == b.id // identity function
        );
    }

    setVisible(isVisible) {
        if (isVisible != this.show) {
            this.dataSource.show = isVisible;
            this.trackInfoDataSource.show = isVisible;
            this.trajectoryDataSource.show = isVisible;
            this.show = isVisible;
        }
    }
}


var trackSources = []; // ordered list of TrackSource objects
var selectedTrackSource = undefined;

var trackEntryFilter = noTrackEntryFilter;
var trackEntryView = undefined; // the UI element to display trackEntries
var trackSourceView = undefined; // the UI element for our track sources

function noTrackEntryFilter(track) { return true; } // all tracks are displayed

export function initialize() {
    trackSourceView = initTrackSourceView();
    trackEntryView = initTrackEntryView();

    uiCesium.setEntitySelectionHandler(trackSelection);
    ws.addWsHandler(handleWsTrackMessages);

    return true;
}

function initTrackSourceView() {
    let view = ui.getList("tracks.sources");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "show", width: "1rem", attrs: [], map: e => e.show ? "●" : "" },
            { name: "id", width: "5rem", attrs: ["alignLeft"], map: e => e.id },
            { name: "size", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.trackEntries.size.toString() },
            { name: "date", width: "6rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalTimeString(e.date) }
        ]);
    }
    return view;
}

function initTrackEntryView() {
    let view = ui.getList("tracks.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "assets", width: "2rem", attrs: [], map: te => te.assetDisplay() },
            { name: "id", width: "5rem", attrs: ["alignLeft"], map: te => te.id },
            { name: "date", width: "5rem", attrs: ["fixed", "alignRight"], map: te => util.toLocalTimeString(te.track.date) }
        ]);
    }
    return view;
}


// intercept adding/removing entities to enable the non-flicker hack.
// as of Cesium 1.89 single polylines/walls (and possible other entity properties) within a DataSource
// cause flicker on update when using ConstantProperty, and get corrupted when using CallbackProperty
// (draw object end point inserted at splice point).

function addTrackSymbolEntity(ds, e) {
    ds.entities.add(e);
}

function removeTrackSymbolEntity(ds, e) {
    ds.entities.remove(e);
}

function addTrackInfoEntity(ds, e) {
    ds.entities.add(e);
}

function removeTrackInfoEntity(ds, e) {
    ds.entities.remove(e);
}

function addTrajectoryEntity(ds, e) {
    let e0 = Object.assign({}, e);
    e0.id = e.id + "-0";
    if (e.wall) {
        e0.wall = e.wall.clone();
        e0.wall.positions = e.wall.positions.getValue().slice(0, 2);
    } else {
        e0.polyline = e.polyline.clone();
        e0.polyline.positions = e.polyline.positions.getValue().slice(0, 2);
    }
    ds.entities.add(e0);
    //--- end flicker hack

    ds.entities.add(e);
}

function removeTrajectoryEntity(ds, e) {
    ds.entities.removeById(e.id + "-0"); // flicker hack
    ds.entities.remove(e);
}

function trackSelection() {
    let sel = uiCesium.getSelectedEntity();
    if (sel && sel._uiTrackEntry) {
        let te = sel._uiTrackEntry;
        if (te.trackSource !== selectedTrackSource) {
            if (selectedTrackSource) ui.clearSelectedListItem(trackEntryView);
            ui.setSelectedListItem(trackSourceView, te.trackSource);
        }
        ui.setSelectedListItem(trackEntryView, te);
    } else {
        ui.clearSelectedListItem(trackEntryView);
    }
}

//--- track related websocket messages

function handleWsTrackMessages(msgType, msg) {
    switch (msgType) {
        case "track":
            handleTrackMessage(msg.track);
            return true;
        case "trackList":
            handleTrackListMessage(msg.trackList);
            return true;
        case "sources":
            handleSources(msg.sources);
            return true;
        default:
            return false;
    }
}

function handleSources(sources) {
    trackSources = sources.map(s => new TrackSource(util.intern(s)));

    //--- add dataSources according to type (track, trackInfo, trajectory) and specified order
    trackSources.forEach(ts => uiCesium.addDataSource(ts.dataSource));
    trackSources.forEach(ts => uiCesium.addDataSource(ts.trackInfoDataSource));
    trackSources.forEach(ts => uiCesium.addDataSource(ts.trajectoryDataSource));

    ui.setListItems(trackSourceView, trackSources);
    ui.setSelectedListItem(trackSourceView, trackSources[0]);
}

function handleTrackMessage(track) {
    //console.log(JSON.stringify(track));
    updateTrackEntries(track);
}

function handleTrackListMessage(tracks) { // bulk update
    for (track of tracks) updateTrackEntries(track);
}

function updateTrack(track, te, pos, attitude) {
    let ts = te.trackSource;
    let trackEntries = ts.trackEntries;
    let trackEntryList = ts.trackEntryList;

    if (isTrackTerminated(track)) { // remove
        if (trackEntryFilter(te)) {
            trackEntryList.remove(te)
            if (ts === selectedTrackSource) {
                ui.removeListItem(trackEntryView, te);
            }
            trackEntries.delete(te.id);
        }
        removeAssets(te);

    } else { // update
        te.track = track;
        te.trace.push(track);

        if (te.assets.symbol) updateTrackSymbolAsset(te, pos, attitude);
        if (te.assets.info) updateTrackInfoAsset(te, pos);
        if (te.assets.trajectory) updateTrajectoryAsset(te);

        if (trackEntryFilter(te)) {
            if (hasTrackIdChanged(track)) {
                trackEntryList.remove(te);
                if (ts === selectedTrackSource) {
                    ui.removeListItem(trackEntryView, te);
                }

                trackEntries.remove(te.id);
                te.id = track.label;
                trackEntries.set(te.id, te);

                let idx = trackEntryList.insert(te);
                if (ts === selectedTrackSource) {
                    ui.insertListItem(trackEntryView, te, idx);
                }
            } else {
                if (ts === selectedTrackSource) {
                    ui.updateListItem(trackEntryView, te);
                }
            }
        }
    }
}

function addTrack(ts, track, pos, attitude) {
    let trackEntries = ts.trackEntries;
    let trackEntryList = ts.trackEntryList;

    //console.log("add track: " + JSON.stringify(track));
    let assets = new TrackAssets(null, null, null);
    let te = new TrackEntry(track, assets, ts);
    trackEntries.set(te.id, te);
    te.trace.push(track);

    if (ts.show) {
        if (trackEntryFilter(te)) {
            assets.symbol = createTrackSymbolAsset(te, pos, attitude);
            assets.info = createTrackInfoAsset(te, pos);
            // trajectory only created on demand

            let idx = trackEntryList.insert(te);
            if (ts === selectedTrackSource) {
                ui.insertListItem(trackEntryView, te, idx);
            }

            addTrackSymbolEntity(ts.dataSource, assets.symbol);
            addTrackInfoEntity(ts.trackInfoDataSource, assets.info);
        }
    }
}

function updateTrackEntries(track) {
    // avoid gazillions of equal string objects
    track.id = util.intern(track.id);
    track.label = util.intern(track.label);
    track.src = util.intern(track.src);

    let ts = trackSources.find(ts => ts.id === track.src);
    if (ts) {
        let pitch = track.pitch ? track.pitch : 0.0;
        let roll = track.roll ? track.roll : 0.0;
        let hpr = Cesium.HeadingPitchRoll.fromDegrees(track.hdg, pitch, roll);

        let pos = Cesium.Cartesian3.fromDegrees(track.lon, track.lat, track.alt);
        let attitude = Cesium.Transforms.headingPitchRollQuaternion(pos, hpr);

        let te = ts.trackEntries.get(track.label);
        if (te) {
            updateTrack(track, te, pos, attitude);
        } else {
            addTrack(ts, track, pos, attitude);
        }

        ts.date = track.date;
        ui.updateListItem(trackSourceView, ts);
    } else {
        console.log("unknown track source: " + track.src);
    }
}

function isTrackTerminated(track) {
    return (track.status & 0x0c); // 4: dropped, 8: completed
}

function hasTrackIdChanged(track) {
    return (track.status & 0x20);
}

function getTrackColor(trackSourceId) {
    let trackColor = config.trackColors.get(trackSourceId);
    if (!trackColor) trackColor = config.trackColor;
    return trackColor;
}

// TODO - support categories (colors etc)
function createTrackSymbolAsset(trackEntry, pos, attitude) {
    let trackSource = trackEntry.trackSource;
    let track = trackEntry.track;
    let trackColor = getTrackColor(trackSource.id);
    let entityPrototype = trackSource.entityPrototype;

    let sym = new Cesium.Entity({
        id: track.label,
        position: pos,
        orientation: attitude,

        point: entityPrototype ? entityPrototype.point : {
            pixelSize: config.trackPointSize,
            color: trackColor,
            outlineColor: config.trackPointOutlineColor,
            outlineWidth: config.trackPointOutlineWidth,
            distanceDisplayCondition: config.trackPointDC
        },
        model: entityPrototype ? entityPrototype.model : {
            uri: config.trackModel,
            color: trackColor,
            //colorBlendMode: Cesium.ColorBlendMode.HIGHLIGHT,
            colorBlendMode: Cesium.ColorBlendMode.MIX,
            colorBlendAmount: 0.7,
            silhouetteColor: config.trackModelOutlineColor,
            silhouetteSize: config.trackModelOutlineWidth,
            minimumPixelSize: config.trackModelSize,
            distanceDisplayCondition: config.trackModelDC
        },
        label: {
            text: track.label,
            scale: 0.8,
            horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
            verticalOrigin: Cesium.VerticalOrigin.TOP,
            font: config.trackLabelFont,
            fillColor: trackColor,
            showBackground: true,
            backgroundColor: config.trackLabelBackground, // alpha does not work against model
            outlineColor: trackColor,
            outlineWidth: 1,
            pixelOffset: config.trackLabelOffset,
            //disableDepthTestDistance: config.minLabelDepth,
            disableDepthTestDistance: Number.POSITIVE_INFINITY,
            distanceDisplayCondition: config.trackLabelDC
        }
        // track paths are separate entities
    });

    sym._uiTrackEntry = trackEntry; // for entity selection

    if (!entityPrototype) trackSource.entityPrototype = sym; // first one

    return sym;
}

function updateTrackSymbolAsset(trackEntry, pos, attitude) {
    let sym = trackEntry.assets.symbol;
    sym.position = pos;
    sym.orientation = attitude;
}

function createTrackInfoAsset(trackEntry, pos) {
    let trackColor = getTrackColor(trackEntry.trackSource.id);

    return new Cesium.Entity({
        id: trackInfoLabel(trackEntry),
        position: pos,

        label: {
            font: config.trackInfoFont,
            scale: 0.8,
            horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
            verticalOrigin: Cesium.VerticalOrigin.TOP,
            fillColor: trackColor,
            showBackground: true,
            backgroundColor: config.trackLabelBackground, // alpha does not work against model
            outlineColor: trackColor,
            outlineWidth: 1,
            pixelOffset: config.trackInfoOffset,
            //disableDepthTestDistance: config.minLabelDepth,
            disableDepthTestDistance: Number.POSITIVE_INFINITY,
            distanceDisplayCondition: config.trackInfoDC
        }
    });
}

function updateTrackInfoAsset(trackEntry, pos) {
    let info = trackEntry.assets.info;
    info.label.text = trackInfoLabel(trackEntry);
    info.position = pos;
}

const DOWN = '▽'; // '-' '▽' '↓' '⊤' '⎤' '⇩'
const LEFT = '◁'; // '-' '◁' '↶' '⟲'
const UP = '△'; // '+' '△' '↑' '⊥' '⎦'
const RIGHT = '▷'; // '+' '▷' '↷' '⟳'
const SAME = ' '; // ' ' '⊣' '⎢'

function trackInfoLabel(trackEntry) {
    let track = trackEntry.track;

    let fl = util.toRightAlignedString(util.metersToFlightLevel(track.alt), 3);
    let hdg = util.toRightAlignedString(Math.round(track.hdg), 3);
    let spd = util.toRightAlignedString(Math.round(util.metersPerSecToKnots(track.spd)), 3);

    var dHdg = 0;
    var dSpd = 0;
    var dAlt = 0;
    let tRef = getRefPoint(trackEntry, 5000);
    if (tRef) {
        dHdg = headingTrend(tRef.hdg, track.hdg);
        dSpd = track.spd - tRef.spd;
        dAlt = track.alt - tRef.alt;
    }
    let ind1 = (dAlt > 0) ? UP : ((dAlt < 0) ? DOWN : SAME);
    let ind2 = (dHdg > 0) ? RIGHT : ((dHdg < 0) ? LEFT : SAME);
    let ind3 = (dSpd > 0) ? UP : ((dSpd < 0) ? DOWN : SAME);

    //return `FL${fl} ${hdg}° ${spd}kn`;
    return `${fl} fl ${ind1}\n${hdg} °  ${ind2}\n${spd} kn ${ind3}`;
}

function headingTrend(h1, h2) {
    let d = h2 - h1;
    if (d > 0) return ((d > 180) ? d - 360 : d);
    else if (d < 0) return ((d < -180) ? d + 360 : d);
    else return 0;
}

function getRefPoint(te, dur) {
    let track = te.track;
    let date = track.date;
    let trace = te.trace;

    let i = 0;
    while (i < trace.size) {
        let p = trace.reverseAt(i);
        if (date - p.date > dur) return p;
        i++;
    }

    return null;
}

function removeAssets(te) {
    let assets = te.assets;
    if (assets.symbol) removeTrackSymbolEntity(assets.symbol);
    if (assets.info) removeTrackInfoEntity(assets.info);
    if (assets.trajectory) removeTrajectoryEntity(assets.trajectory);
}

function createTrajectoryAssetPositions(trackEntry) {
    let trace = trackEntry.trace;
    let positions = new Array(trace.size);
    let i = 0;
    trace.forEach(t => {
        positions[i++] = Cesium.Cartesian3.fromDegrees(t.lon, t.lat, t.alt);
    });
    return positions;
}

function createTrajectoryAsset(trackEntry, isWall) {
    // does not work for polylines (wrong endpoints) or walls (no show) if number of points is changing
    //let posCallback = new Cesium.CallbackProperty(() => { trackEntry.assets.trajectoryPositions }, false);
    let trackColor = getTrackColor(trackEntry.trackSource.id);

    if (isWall) {
        return new Cesium.Entity({
            id: trackEntry.id,
            wall: {
                positions: trackEntry.assets.trajectoryPositions, // posCallback,
                show: true,
                fill: true,
                material: Cesium.Color.fromAlpha(trackColor, 0.2),
                outline: true,
                outlineColor: Cesium.Color.fromAlpha(trackColor, 0.5),
                outlineWidth: config.trackPathWidth,
                distanceDisplayCondition: config.trackPathDC
            }
        });
    } else {
        return new Cesium.Entity({
            id: trackEntry.id,
            polyline: {
                positions: trackEntry.assets.trajectoryPositions, // posCallback,
                clampToGround: false,
                width: config.trackPathWidth,
                material: trackColor,
                distanceDisplayCondition: config.trackPathDC
            }
        });
    }
}

function updateTrajectoryAsset(trackEntry) {
    let positions = trackEntry.assets.trajectoryPositions;
    if (positions) {
        let track = trackEntry.track;
        positions.push(Cesium.Cartesian3.fromDegrees(track.lon, track.lat, track.alt));

        let entity = trackEntry.assets.trajectory.wall ? trackEntry.assets.trajectory.wall : trackEntry.assets.trajectory.polyline;
        entity.positions = positions; // this creates a new ConstantProperty and flickers
    }
}


//--- track queries

const idQuery = /^ *id *= *(.+)$/;
// ..and more to follow

function getTrackEntryFilter(query) {
    if (!query || query == "*") {
        return noTrackEntryFilter;
    } else {
        let res = query.match(idQuery);
        if (res) {
            let idQuery = util.glob2regexp(res[1]);
            return (idQuery == '*') ? noTrackEntryFilter : te => te.id.match(idQuery);
        }
        return null;
    }
}

function resetTrackEntryList() {
    if (selectedTrackSource) {
        selectedTrackSource.trackEntryList.clear();
        selectedTrackSource.trackEntries.forEach((te, id, map) => {
            if (trackEntryFilter(te)) selectedTrackSource.trackEntryList.insert(te);
        });
        ui.setListItems(trackEntryView, selectedTrackSource.trackEntryList);
    }
}

function resetTrackEntryAssets() {
    if (selectedTrackSource) {
        selectedTrackSource.trackEntries.forEach((te, id, map) => {
            let assets = te.assets;

            if (trackEntryFilter(te)) {
                let track = te.track;
                let pitch = track.pitch ? track.pitch : 0.0;
                let roll = track.roll ? track.roll : 0.0;
                let hpr = Cesium.HeadingPitchRoll.fromDegrees(track.hdg, pitch, roll);
                let pos = Cesium.Cartesian3.fromDegrees(track.lon, track.lat, track.alt);
                let attitude = Cesium.Transforms.headingPitchRollQuaternion(pos, hpr);

                if (!assets.symbol) {
                    assets.symbol = createTrackSymbolAsset(te, pos, attitude);
                    // add info here
                    addTrackSymbolEntity(selectedTrackSource.dataSource, assets.symbol);
                }
                if (!assets.info) {
                    assets.info = createTrackInfoAsset(te, pos);
                    addTrackInfoEntity(selectedTrackSource.trackInfoDataSource, assets.info);
                }
            } else { // filtered, check if we need to remove from viewer entities
                if (assets.symbol) {
                    removeTrackSymbolEntity(selectedTrackSource.dataSource, assets.symbol);
                    assets.symbol = null;
                }
                if (assets.info) {
                    removeTrackInfoEntity(selectedTrackSource.trackInfoDataSource, assets.info);
                    assets.info = null;
                }
                if (assets.trajectory) {
                    removeTrajectoryEntity(selectedTrackSource.trajectoryDataSource, assets.trajectory);
                    assets.trajectory = null;
                    assets.trajectoryPositions = null; // don't do this before removing the asset
                }
            }
        });
    }
}

//--- interaction (those cannot be called without a DOM event argument)

ui.exportToMain(function queryTracks(event) {
    let input = ui.getFieldValue(event);

    let newFilter = getTrackEntryFilter(input);
    if (newFilter) {
        trackEntryFilter = newFilter;
        resetTrackEntryList();
        resetTrackEntryAssets();
    }
});

ui.exportToMain(function selectTrack(event) {
    let te = event.detail.curSelection;
    if (te) {
        if (te.assets.symbol) uiCesium.setSelectedEntity(te.assets.symbol);
        if (te.assets.trajectory) {
            ui.setCheckBox("tracks.path", true);
            if (te.assets.trajectory.wall) ui.selectRadio("tracks.wall");
            else ui.selectRadio("tracks.line");
        } else {
            ui.setCheckBox("tracks.path", false);
            ui.clearRadioGroup("tracks.line");
        }
    } else { // nothing selected
        ui.setCheckBox("tracks.path", false);
        ui.clearRadioGroup("tracks.line");
    }
});

ui.exportToMain(function toggleShowPath(event) {
    let te = ui.getSelectedListItem(trackEntryView);
    if (te) {
        if (ui.isCheckBoxSelected(event)) {
            let isWall = ui.isRadioSelected("tracks.wall");
            if (!isWall) ui.selectRadio("tracks.line");

            te.assets.trajectoryPositions = createTrajectoryAssetPositions(te);
            te.assets.trajectory = createTrajectoryAsset(te, isWall);
            addTrajectoryEntity(te.trackSource.trajectoryDataSource, te.assets.trajectory);

        } else {
            if (te.assets.trajectory) {
                removeTrajectoryEntity(te.trackSource.trajectoryDataSource, te.assets.trajectory);
                te.assets.trajectory = null;
                te.assets.trajectoryPositions = null;
                ui.clearRadioGroup("tracks.line");
            }
        }
    }
});

ui.exportToMain(function setLinePath(event) {
    let te = ui.getSelectedListItem(trackEntryView);
    if (te) {
        if (te.assets.trajectory) {
            if (te.assets.trajectory.wall) {
                removeTrajectoryEntity(te.trackSource.trajectoryDataSource, te.assets.trajectory);
                te.assets.trajectory = createTrajectoryAsset(te, false);
                addTrajectoryEntity(te.trackSource.trajectoryDataSource, te.assets.trajectory);
            }
        } else {
            te.assets.trajectoryPositions = createTrajectoryAssetPositions(te);
            te.assets.trajectory = createTrajectoryAsset(te, false);
            addTrajectoryEntity(te.trackSource.trajectoryDataSource, te.assets.trajectory);
            ui.setCheckBox("tracks.path", true);
        }
    }
});

ui.exportToMain(function setWallPath(event) {
    let te = ui.getSelectedListItem(trackEntryView);
    if (te) {
        if (te.assets.trajectory) {
            if (te.assets.trajectory.polyline) {
                removeTrajectoryEntity(te.trackSource.trajectoryDataSource, te.assets.trajectory);
                te.assets.trajectory = createTrajectoryAsset(te, true);
                addTrajectoryEntity(te.trackSource.trajectoryDataSource, te.assets.trajectory);
            }
        } else {
            te.assets.trajectoryPositions = createTrajectoryAssetPositions(te);
            te.assets.trajectory = createTrajectoryAsset(te, true);
            addTrajectoryEntity(te.trackSource.trajectoryDataSource, te.assets.trajectory);
            ui.setCheckBox("tracks.path", true);
        }
    }
});

ui.exportToMain(function resetPaths() {
    if (selectedTrackSource) {
        selectedTrackSource.trackEntryList.forEach(te => {
            if (te.assets.trajectory) {
                removeTrajectoryEntity(selectedTrackSource.trajectoryDataSource, te.assets.trajectory);
                te.assets.trajectory = null;
                te.assets.trajectoryPositions = null;
            }
        });
        ui.clearSelectedListItem(trackEntryView);
        ui.setCheckBox("tracks.path", false);
        ui.clearRadioGroup(ui.getRadio("tracks.wall"));
        uiCesium.clearSelectedEntity();
    }
});

ui.exportToMain(function selectSource(event) {
    selectedTrackSource = event.detail.curSelection;
    if (selectedTrackSource) {
        ui.setListItems(trackEntryView, selectedTrackSource.trackEntryList);
    } else {
        ui.clearList(trackEntryView);
    }
});

ui.exportToMain(function toggleShowSource(event) {
    if (selectedTrackSource) {
        selectedTrackSource.setVisible(ui.toggleMenuItemCheck(event));
    }
});

ui.exportToMain(function showAllSources(event) {
    console.log("@@ TBD: show all sources");
});
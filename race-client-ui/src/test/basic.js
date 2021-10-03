/* example app code for basic.html */

const LINE_PATH = "l";
const WALL_PATH = "w";

var trackList = [
    ["", "UAL1684", "00:00:00"],
    [LINE_PATH, "UPS2958", "00:00:00"],
    ["", "PCM7700", "00:00:00"],
    [WALL_PATH, "ASA223", "00:00:00"],
    ["", "PCM7702", "00:00:00"]
];

//--- ui initialization and callbacks

function initializeData() {
    console.log("initializing data");

    uiSetField("console.clock.date", "Thu, 09-15-2021");
    uiSetField("console.clock.time", "  10:13:12 Z");
    uiSetField("console.clock.elapsed", "01:02:03");

    uiSetField("console.position.latitude", "37.54323");
    uiSetField("console.position.longitude", "-121.87432");

    uiSetListItemKeyColumn("console.tracks.list", 1);
    uiSetListItems("console.tracks.list", trackList);

    uiSetChoiceItems("console.tracks.channel", [
        "SFDPS",
        "TAIS",
        "ASDE-X"
    ], 0);
}

function selectTrack(event) {
    let v = uiGetSelectedListItem(event);
    let idx = uiGetSelectedListItemIndex(event);
    console.log("selected " + idx + ": " + v);

    let pathOpt = uiNonEmptyString(v[0]);
    if (pathOpt) {
        uiSetCheckBox("console.tracks.showPath", true);
        if (pathOpt == "l") uiSelectRadio("console.tracks.showLinePath");
        else if (pathOpt == "w") uiSelectRadio("console.tracks.showWallPath");
    } else {
        uiSetCheckBox("console.tracks.showPath", false);
        uiClearRadiosOf("console.tracks.options");
    }
}

function doSomethingWithSelectedTrack() {
    console.log("exec doSomethingWithSelectedTrack");
}

function toggleCheck(event) {
    let isChecked = uiToggleMenuItemCheck(event);
    uiSetMenuItemDisabled("console.tracks.list.condMenuItem", isChecked);
    console.log("checked: " + isChecked);
}

function doSomethingDifferent() {
    console.log("and now to something completely different");
}

function doSomethingConditional() {
    if (uiIsMenuItemDisabled("console.tracks.list.condMenuItem")) throw "this should have been disabled!";
    else console.log("do something if unchecked");
}

function showTrackPath(event) {
    let isChecked = uiToggleCheckbox(event);
    let selIdx = uiGetSelectedListItemIndex("console.tracks.list");

    console.log("show track path: " + isChecked);
    if (isChecked) {
        uiSelectRadio("console.tracks.showLinePath");
    } else {
        uiClearRadiosOf("console.tracks.options");
    }

    if (selIdx >= 0) {
        let track = trackList[selIdx];
        if (!isChecked) track[0] = "";
        else track[0] = LINE_PATH;
        uiUpdateListItem("console.tracks.list", selIdx, track);
    }
}

function resetTracks() {
    uiClearListSelection("console.tracks.list");
    uiSetCheckBox("console.tracks.showPath", false);
    uiClearRadiosOf("console.tracks.options");

    for (var i = 0; i < trackList.length; i++) {
        let track = trackList[i];
        track[0] = "";
        uiUpdateListItem("console.tracks.list", i, track);
    }

    console.log("Reset tracks");
}

function selectPath(event, pathType, pathSymbol) {
    let selIdx = uiGetSelectedListItemIndex("console.tracks.list");
    if (uiSelectRadio(event)) {
        uiSetCheckBox("console.tracks.showPath");
        console.log(pathType + " path selected: " + selIdx);
        if (selIdx >= 0) {
            let track = trackList[selIdx];
            track[0] = pathSymbol;
            uiUpdateListItem("console.tracks.list", selIdx, track);
        }
    }
}

function selectLinePath(event) {
    selectPath(event, "line", LINE_PATH);
}

function selectWallPath(event) {
    selectPath(event, "wall", WALL_PATH);
}

function queryTracks(event) {
    let input = uiGetFieldValue(event);
    console.log("query tracks: " + input);
}

function selectChannel(event) {
    let input = uiGetSelectedChoiceValue(event);
    console.log("select channel: " + input);
}
#!/usr/bin/env python3
"""
End-to-end helper for OBS Layer Cast: drives a running OBS through obs-websocket (v5).

  obs_e2e.py setup        create/refresh a "LayerCast E2E" scene collection with one source per layer
  obs_e2e.py shoot DIR    save a PNG of every LayerCast source (and the whole scene) into DIR
  obs_e2e.py inputs       list LayerCast inputs and their settings

Connection: --host/--port/--password, or the values from OBS' websocket config file.
"""
import argparse
import base64
import json
import os
import pathlib
import sys
import time

import obsws_python as obs

KIND = "layercast_layer_source"
COLLECTION = "LayerCast E2E"
SCENE = "LayerCast"
DEFAULT_LAYERS = ["game", "debug", "chat", "bossbar", "scoreboard", "tablist", "hotbar", "title", "effects", "nametags"]


def default_config_path():
    if sys.platform == "darwin":
        return pathlib.Path.home() / "Library/Application Support/obs-studio/plugin_config/obs-websocket/config.json"
    if os.name == "nt":
        return pathlib.Path(os.environ["APPDATA"]) / "obs-studio/plugin_config/obs-websocket/config.json"
    return pathlib.Path.home() / ".config/obs-studio/plugin_config/obs-websocket/config.json"


def connect(args):
    password = args.password
    port = args.port
    if password is None:
        cfg = json.loads(default_config_path().read_text())
        password = cfg.get("server_password") if cfg.get("auth_required", True) else None
        port = port or cfg.get("server_port", 4455)
    deadline = time.time() + args.wait
    while True:
        try:
            return obs.ReqClient(host=args.host, port=port or 4455, password=password, timeout=10)
        except Exception as e:  # OBS may still be starting
            if time.time() > deadline:
                raise
            time.sleep(1)


def setup(client, layers):
    collections = client.get_scene_collection_list().scene_collections
    if COLLECTION not in collections:
        client.create_scene_collection(COLLECTION)
        time.sleep(2)
    elif client.get_scene_collection_list().current_scene_collection_name != COLLECTION:
        client.set_current_scene_collection(COLLECTION)
        time.sleep(2)
    scenes = [s["sceneName"] for s in client.get_scene_list().scenes]
    if SCENE not in scenes:
        client.create_scene(SCENE)
    client.set_current_program_scene(SCENE)
    existing = {i["inputName"] for i in client.get_input_list(KIND).inputs}
    for layer in layers:
        name = f"lc-{layer}"
        settings = {"channel": "default", "layer": layer}
        if name in existing:
            client.set_input_settings(name, settings, True)
        else:
            client.create_input(SCENE, name, KIND, settings, True)
    print("scene ready:", ", ".join(f"lc-{l}" for l in layers))


def shoot(client, out_dir):
    out = pathlib.Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    names = [i["inputName"] for i in client.get_input_list(KIND).inputs] + [SCENE]
    for name in names:
        try:
            resp = client.get_source_screenshot(name, "png", None, None, -1)
        except Exception as e:
            print(f"{name}: screenshot failed: {e}")
            continue
        data = resp.image_data.split(",", 1)[1]
        path = out / f"{name}.png"
        path.write_bytes(base64.b64decode(data))
        print(f"{name}: {path}")


def inputs(client):
    for i in client.get_input_list(KIND).inputs:
        s = client.get_input_settings(i["inputName"]).input_settings
        print(i["inputName"], s)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("command", choices=["setup", "shoot", "inputs"])
    p.add_argument("dir", nargs="?")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int)
    p.add_argument("--password")
    p.add_argument("--wait", type=float, default=30.0, help="seconds to wait for OBS to accept connections")
    p.add_argument("--layers", default=",".join(DEFAULT_LAYERS))
    args = p.parse_args()
    client = connect(args)
    if args.command == "setup":
        setup(client, [l for l in args.layers.split(",") if l])
    elif args.command == "shoot":
        shoot(client, args.dir or "obs-shots")
    else:
        inputs(client)


if __name__ == "__main__":
    main()

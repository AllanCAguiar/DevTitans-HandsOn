#!/bin/bash

cd ~/android/lineage
repo forall -c 'git reset --hard HEAD && git clean -fdx'
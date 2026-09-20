ROOT := ../..
PYTHON ?= python3
CORE := INSCALC INSVAL INSPACK INSDATE INSRATE
AS := $(ROOT)/as370/as370
LD := $(ROOT)/ld370/ld370

.PHONY: core batch tools lint test verify-golden
tools:
	$(MAKE) -C $(ROOT) as370/as370 ld370/ld370 file370/file370

core: tools
	$(PYTHON) tools/build.py core

batch: tools
	$(PYTHON) tools/build.py batch $(foreach dir,$(MACLIBS),--maclib $(dir))

lint:
	$(PYTHON) tools/lint.py
	$(PYTHON) -m compileall -q tools tests

test: lint
	$(PYTHON) -m unittest discover -s tests -v

verify-golden:
	$(PYTHON) tools/corpus.py verify

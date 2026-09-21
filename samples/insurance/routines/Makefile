PYTHON ?= python3
HARNESS := harness
EVIDENCE ?= $(HOME)/mvs-demo/routines-evidence
MACLIB ?= $(HOME)/mvs-demo/export/ascii
BACKENDS := ifox-iewl as370-iewl as370-ld370
# Evidence folders consumed by `make contract`; default to the committed compact bundle.
OBSERVED ?= $(foreach b,$(BACKENDS),evidence/$(b))

.PHONY: lint build test fixtures fixtures-check controls contract derive guest jobs

lint:
	$(PYTHON) $(HARNESS)/lint.py
	$(PYTHON) -m compileall -q $(HARNESS) tests

# Host-assemble/link the driver with the frozen core decks (needs `make -C .. core`).
build:
	cd $(HARNESS) && $(PYTHON) -c "from pathlib import Path; import run_guest; \
	    run_guest.host_build(Path('$(MACLIB)'))"

test: lint build
	$(PYTHON) -m unittest discover -s tests -v

fixtures:
	cd $(HARNESS) && $(PYTHON) fixtures.py build

# Non-mutating: verifies tracked fixtures against an in-memory render and every declared input hash.
fixtures-check:
	cd $(HARNESS) && $(PYTHON) fixtures.py check

controls:
	cd $(HARNESS) && $(PYTHON) controls.py --report ../evidence/controls.json --evidence ../evidence

contract:
	cd $(HARNESS) && $(PYTHON) contract.py --out ../contract \
	    $(foreach dir,$(OBSERVED),--observed ../$(dir))

# Re-derive comparison.json/observed.json from retained raw captures under $(EVIDENCE)/<backend>
# with the current comparator/decoder. Never touches capture.bin or run.json (the guest receipt).
derive:
	cd $(HARNESS) && for b in $(BACKENDS); do \
	    [ -f $(EVIDENCE)/$$b/capture.bin ] || continue; \
	    $(PYTHON) compare.py --capture $(EVIDENCE)/$$b/capture.bin \
	        --driver-symbols $(EVIDENCE)/$$b/host/INSDRV.sym --report $(EVIDENCE)/$$b/comparison.json; \
	    $(PYTHON) observe.py --capture $(EVIDENCE)/$$b/capture.bin --out $(EVIDENCE)/$$b/observed.json; \
	done

# Real guest execution (Hercules/TK5). BACKEND is one of ifox-iewl, as370-iewl, as370-ld370.
guest:
	cd $(HARNESS) && $(PYTHON) run_guest.py --backend $(BACKEND) \
	    --evidence $(EVIDENCE)/$(BACKEND) --prefix $(PREFIX) --maclib $(MACLIB)

jobs:
	cd $(HARNESS) && $(PYTHON) run_jobs.py --backend $(BACKEND) \
	    --evidence $(EVIDENCE)/jobs-$(BACKEND) --prefix $(PREFIX) --maclib $(MACLIB)

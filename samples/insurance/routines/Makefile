PYTHON ?= python3
HARNESS := harness
EVIDENCE ?= $(HOME)/mvs-demo/routines-evidence
MACLIB ?= $(HOME)/mvs-demo/export/ascii

.PHONY: lint build test fixtures controls contract observe guest jobs

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

controls:
	cd $(HARNESS) && $(PYTHON) controls.py --report ../evidence/controls.json

contract:
	cd $(HARNESS) && $(PYTHON) contract.py --out ../contract \
	    $(foreach dir,$(OBSERVED),--observed $(dir))

# Real guest execution (Hercules/TK5). BACKEND is one of ifox-iewl, as370-iewl, as370-ld370.
guest:
	cd $(HARNESS) && $(PYTHON) run_guest.py --backend $(BACKEND) \
	    --evidence $(EVIDENCE)/$(BACKEND) --prefix $(PREFIX) --maclib $(MACLIB)

jobs:
	cd $(HARNESS) && $(PYTHON) run_jobs.py --backend $(BACKEND) \
	    --evidence $(EVIDENCE)/jobs-$(BACKEND) --prefix $(PREFIX) --maclib $(MACLIB)

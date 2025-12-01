# -*- coding: utf-8 -*-
"""
Example DAGs test (import-errors only).

Best practice: do NOT mutate DagBag. Skip path-scoped cases that require
unavailable infra (e.g., on-prem shares behind a firewall) at the test layer.
"""
import logging
import os
from contextlib import contextmanager
from typing import Dict, Iterable, List, Optional, Tuple

import pytest
from airflow.models import DagBag


# --- Configuration -----------------------------------------------------------

# Paths are compared against values AFTER strip_path_prefix(), i.e., relative to AIRFLOW_HOME if set.
EXCLUDED_REL_PATHS = {
    os.path.normpath("dags/chdevops_filesystem_dags/omgch_onpremise_to_gcs_source.py"),
    os.path.normpath("dags/chdevops_filesystem_dags/omgch_sync_tv_singlefiles_to_bucket.py"),
}


# --- Utilities ---------------------------------------------------------------

@contextmanager
def suppress_logging(namespace: str):
    """Temporarily disable logging for a given logger namespace."""
    logger = logging.getLogger(namespace)
    old_value = logger.disabled
    logger.disabled = True
    try:
        yield
    finally:
        logger.disabled = old_value


def _strip_path_prefix(path: str) -> str:
    """
    Make dag file path relative to AIRFLOW_HOME (if available); otherwise return as-is.
    Also normalize path for stable comparisons across OSes.
    """
    airflow_home = os.environ.get("AIRFLOW_HOME")
    if airflow_home:
        try:
            return os.path.normpath(os.path.relpath(path, airflow_home))
        except Exception:
            # Fallback to normalized absolute/unchanged path on any error
            return os.path.normpath(path)
    return os.path.normpath(path)


def _collect_import_errors() -> List[Tuple[Optional[str], Optional[str]]]:
    """
    Return [(rel_path, error_message), ...] for all import errors in DagBag.
    A sentinel (None, None) is prepended so pytest always parametrizes at least one item.
    """
    with suppress_logging("airflow"):
        dag_bag = DagBag(include_examples=False)

    # type: Dict[str, str]
    import_errs: Dict[str, str] = dag_bag.import_errors

    items: List[Tuple[Optional[str], Optional[str]]] = [(None, None)]
    for abs_path, err in import_errs.items():
        rel_path = _strip_path_prefix(abs_path)
        items.append((rel_path, err.strip() if err else err))
    return items


# --- Tests -------------------------------------------------------------------

@pytest.mark.parametrize(
    "rel_path,rv",
    _collect_import_errors(),
    ids=[p or "sentinel" for p, _ in _collect_import_errors()],
)
def test_file_imports(rel_path: Optional[str], rv: Optional[str]) -> None:
    """
    Fail on import errors, except for EXCLUDED_REL_PATHS which are skipped.
    """
    # Skip guarded/on-premise DAGs
    if rel_path and os.path.normpath(rel_path) in EXCLUDED_REL_PATHS:
        pytest.skip("Excluded: requires on-prem resources behind company firewall")

    # Fail if there is an actual import error
    if rel_path and rv:
        pytest.fail(f"{rel_path} failed to import with message:\n{rv}")

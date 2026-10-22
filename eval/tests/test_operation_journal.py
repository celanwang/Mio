import importlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[2] / 'skills/tencent-ride/scripts'
sys.path.insert(0, str(SCRIPTS))
bridge = importlib.import_module('personal_agent_bridge')

class OperationJournalTest(unittest.TestCase):
    logical = '11111111-1111-4111-8111-111111111111'
    scope = 'a' * 64

    def test_atomic_receipt_is_correlated_and_redacted(self):
        with tempfile.TemporaryDirectory() as directory:
            account = Path(directory)
            bridge.write_operation_receipt(account, self.logical, self.scope, {'token':'secret','message':'secret'}, 'secret')
            saved = json.loads((account/'operations'/f'{self.logical}.json').read_text())
            self.assertEqual(saved['logicalId'], self.logical)
            self.assertEqual(saved['scopeKey'], self.scope)
            self.assertNotIn('secret', json.dumps(saved))
            self.assertFalse(list((account/'operations').glob('receipt-*')))

    def test_existing_identity_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            account=Path(directory)
            bridge.write_operation_receipt(account,self.logical,self.scope,{'first':True},'')
            with self.assertRaises(FileExistsError):
                bridge.write_operation_receipt(account,self.logical,self.scope,{'different':True},'')
            self.assertEqual(json.loads((account/'operations'/f'{self.logical}.json').read_text())['receipt'],{'first':True})

    def test_invalid_identity_cannot_escape_directory_or_trigger_submission(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for logical in ['../escape', 'not-a-uuid']:
                with self.assertRaises(ValueError):bridge.write_operation_receipt(root,logical,self.scope,{},'')
            self.assertFalse((root/'operations').exists())
            with patch.object(bridge,'initialize',return_value=(root,root)), patch.object(bridge,'run_cli') as submit:
                with self.assertRaises(ValueError):bridge.execute({'command':'create-order','parameters':{},'logicalOperationId':'../escape'})
                submit.assert_not_called()

    def test_existing_receipt_stops_before_any_provider_lookup(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);bridge.write_operation_receipt(root,self.logical,self.scope,{},'')
            with patch.object(bridge,'initialize',return_value=(root,root)), patch.object(bridge,'run_cli') as submit, patch.object(bridge,'current_order') as query:
                with self.assertRaisesRegex(ValueError,'already has a receipt'):
                    bridge.execute({'command':'create-order','parameters':{},'logicalOperationId':self.logical,'operationScope':self.scope})
                submit.assert_not_called();query.assert_not_called()

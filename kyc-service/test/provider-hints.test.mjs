import test from 'node:test';
import assert from 'node:assert/strict';
import {providerHint} from '../provider-hints.mjs';
test('provider diagnostic hints never copy message content or private identifiers',()=>{
 const examples=[
  [{code:'credit_balance_exhausted'},'credit_exhausted'],
  [{message:"Tool web_search is not supported with this model; sk-private"},'tool_model_combination'],
  [{message:"tools cannot be used with text.format json_schema; private_uid"},'tool_output_format_combination'],
  [{message:"Tool web_search is not enabled for project secret-project"},'tool_access_configuration'],
  [{message:"Unknown tool web_search with private user detail"},'tool_type_or_value'],
  [{message:"Invalid filters allowed_domains on web_search"},'tool_option_configuration'],
  [{message:'private sk-sensitive'},'unclassified'],
  [{message:null},'unclassified'],
  [{message:[]},'unclassified'],
  [{message:'Tool rejected: private original message'},'tool_request_unclassified']
 ];
 for(const [input,expected] of examples){assert.equal(providerHint(input),expected);assert.match(providerHint(input),/^[a-z_]+$/);}
});

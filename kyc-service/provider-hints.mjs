// Provider messages are inspected only in memory; no substring is returned.
// This is an operational hint, not proof of the root cause or a retry policy.
export function providerHint(error={}) {
 const m=typeof error?.message==='string'?error.message.slice(0,16000).toLowerCase():'';
 const mentionsTool=/web.?search|hosted tool|built.in tool|tool/.test(m);
 if(/credit_balance_exhausted|insufficient_quota/.test(error?.code||''))return 'credit_exhausted';
 if(!mentionsTool)return 'unclassified';
 if(/structured|json_schema|text[.]format|response_format/.test(m))return 'tool_output_format_combination';
 if(/not supported|unsupported|does not support|not available/.test(m)&&/model/.test(m))return 'tool_model_combination';
 if(/permission|not allowed|not enabled|access denied|organization|organisation|project|verify|verified/.test(m))return 'tool_access_configuration';
 if(/unknown tool|invalid tool|supported values|invalid value|unrecognized|unrecognised/.test(m))return 'tool_type_or_value';
 if(/filters|allowed_domains|search_context_size|return_token_budget/.test(m))return 'tool_option_configuration';
 return 'tool_request_unclassified';
}

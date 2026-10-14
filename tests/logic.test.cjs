const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../dist/app.js'), 'utf8');
const names = ['key', 'fromKey', 'plus', 'minute', 'segmentsOf', 'spanMinutes', 'duration', 'cloneRecord', 'validPeriod', 'normalizePeriod', 'validDateKey', 'validRange', 'monthRange', 'recordsInRange', 'invalid'];
const declarations = source.split(/\r?\n/).filter(line => names.some(name => line.startsWith(`function ${name}(`) || line.startsWith(`const ${name}=`)));
const logic = Function(`${declarations.join('\n')}\nreturn {${names.join(',')}}`)();

test('两段上班之间的空档不计工时，旧记录照旧计算', () => {
  const split = {status:'work',segments:[{start:'09:00',end:'12:00'},{start:'14:00',end:'18:00'}],breakMinutes:0,rate:25};
  assert.equal(logic.invalid(split), '');
  assert.equal(logic.duration(split), 7);
  assert.equal(logic.duration({status:'work',start:'09:00',end:'18:00',breakMinutes:120,rate:25}), 7);
});

test('重叠、倒序和错误休息分钟会被拒绝', () => {
  const base = {status:'work',segments:[{start:'09:00',end:'12:00'},{start:'11:00',end:'18:00'}],breakMinutes:0,rate:25};
  assert.match(logic.invalid(base), /不能重叠/);
  base.segments[1].start = '14:00';
  base.breakMinutes = 420;
  assert.match(logic.invalid(base), /休息时间/);
});

test('单段跨夜班仍可计算', () => {
  const night = {status:'work',start:'22:00',end:'02:00',breakMinutes:0,rate:25};
  assert.equal(logic.invalid(night), '');
  assert.equal(logic.duration(night), 4);
});

test('复制多时段记录不会共享同一组时段', () => {
  const original = {status:'work',segments:[{start:'09:00',end:'12:00'},{start:'14:00',end:'18:00'}],breakMinutes:0,rate:25};
  const copy = logic.cloneRecord(original);
  copy.segments[0].start = '08:00';
  assert.equal(original.segments[0].start, '09:00');
});

test('自选日期可跨月、跨年且包含起止两天', () => {
  const records = {
    '2026-10-07':{status:'work'},
    '2026-10-08':{status:'work'},
    '2026-10-23':{status:'work'},
    '2026-10-24':{status:'rest'},
    '2026-11-07':{status:'work'},
    '2027-01-10':{status:'work'}
  };
  assert.deepEqual(logic.recordsInRange(records,{start:'2026-10-08',end:'2026-10-23'}).map(([k])=>k),['2026-10-08','2026-10-23']);
  assert.deepEqual(logic.recordsInRange(records,{start:'2026-10-24',end:'2026-11-07'}).map(([k])=>k),['2026-11-07']);
  assert.deepEqual(logic.recordsInRange(records,{start:'2026-12-10',end:'2027-01-10'}).map(([k])=>k),['2027-01-10']);
});

test('日期范围校验拒绝倒序和不存在的日期', () => {
  assert.equal(logic.validRange({start:'2026-10-23',end:'2026-10-08'}),false);
  assert.equal(logic.validRange({start:'2026-02-30',end:'2026-03-01'}),false);
  assert.equal(logic.validRange({start:'2026-10-08',end:'2026-11-07'}),true);
  assert.deepEqual(logic.monthRange(new Date(2026,9,14,12)),{start:'2026-10-01',end:'2026-10-31'});
});

test('旧版结算设置仍可读入备份但不限制任意日期查询', () => {
  assert.deepEqual(logic.normalizePeriod({first:8,second:24}),{startDay:8,endDay:23});
  assert.equal(logic.validRange({start:'2026-10-01',end:'2027-01-31'}),true);
});

test('日历格固定宽高，工时与金额占据完整格宽', () => {
  const css = fs.readFileSync(path.join(__dirname, '../dist/mobile-fixes.css'),'utf8');
  assert.match(css,/\.calendar\{grid-auto-rows:76px\}/);
  assert.match(css,/\.day\{[^}]*width:100%;height:100%/);
  assert.match(css,/\.day-num,\.day-label,\.day-money\{[^}]*width:100%/);
});


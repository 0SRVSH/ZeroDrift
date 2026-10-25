import http from 'k6/http';
import { check } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const accountPoolFile = __ENV.ACCOUNT_POOL_FILE || './load-test-accounts.json';
const rawAccounts = open(accountPoolFile);
const duration = __ENV.DURATION || '30s';
const rate = Number(__ENV.RATE || 100);
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || 20);
const maxVUs = Number(__ENV.MAX_VUS || 50);

export const options = {
  scenarios: {
    uniform_transactions: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs,
      maxVUs,
      gracefulStop: '5s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<150', 'p(99)<300'],
  },
};

export function setup() {
    const accounts = JSON.parse(rawAccounts);
    if (!Array.isArray(accounts.retailAccountIds) || accounts.retailAccountIds.length < 4) {
        throw new Error('The load-test account pool must contain at least four retail account IDs.');
    }
    return accounts.retailAccountIds;
}

export default function (retailAccountIds) {
    const accountIds = selectDistinctAccounts(retailAccountIds, 4);
    const idempotencyKey = uuidV4();
    const postings = [
        { accountId: accountIds[0], direction: 'DEBIT', amount: '1.0000' },
        { accountId: accountIds[1], direction: 'DEBIT', amount: '1.0000' },
        { accountId: accountIds[2], direction: 'CREDIT', amount: '1.0000' },
        { accountId: accountIds[3], direction: 'CREDIT', amount: '1.0000' },
    ];

    const response = http.post(
        `${baseUrl}/api/v1/ledger/transactions`,
        JSON.stringify({
            idempotencyKey,
            narration: 'k6 uniform load transaction',
            postings,
        }),
        {
            headers: {
                'Content-Type': 'application/json',
            },
        },
    );

    check(response, {
        'transaction created': (result) => result.status === 201,
    });
}

function selectDistinctAccounts(accountIds, count) {
    const selected = new Set();
    while (selected.size < count) {
        selected.add(accountIds[Math.floor(Math.random() * accountIds.length)]);
    }
    return Array.from(selected);
}

function uuidV4() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
        const random = Math.floor(Math.random() * 16);
        return (character === 'x' ? random : (random & 0x3) | 0x8).toString(16);
    });
}

import http from 'k6/http';
import { check } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const accountPoolFile = __ENV.ACCOUNT_POOL_FILE || './load-test-accounts.json';
const duration = __ENV.DURATION || '30s';

export const options = {
    scenarios: {
        uniform_transactions: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RATE || 5000),
            timeUnit: '1s',
            duration,
            preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 1000),
            maxVUs: Number(__ENV.MAX_VUS || 5000),
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.0001'],
        http_req_duration: ['p(95)<50', 'p(99)<100'],
    },
};

export function setup() {
    const accounts = JSON.parse(open(accountPoolFile));
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
                'Idempotency-Key': idempotencyKey,
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

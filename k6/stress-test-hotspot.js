import http from 'k6/http';
import { check } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const accountPoolFile = __ENV.ACCOUNT_POOL_FILE || './load-test-accounts.json';
const duration = __ENV.DURATION || '30s';

export const options = {
    scenarios: {
        hotspot_credits: {
            executor: 'constant-vus',
            vus: Number(__ENV.VUS || 5000),
            duration,
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

export function setup() {
    const accounts = JSON.parse(open(accountPoolFile));
    if (!Array.isArray(accounts.retailAccountIds) || accounts.retailAccountIds.length === 0) {
        throw new Error('The load-test account pool must contain at least one retail account ID.');
    }
    if (typeof accounts.hotspotMerchantId !== 'string') {
        throw new Error('The load-test account pool must contain the hotspot merchant account ID.');
    }
    return accounts;
}

export default function (accounts) {
    const idempotencyKey = uuidV4();
    const response = http.post(
        `${baseUrl}/api/v1/ledger/transactions`,
        JSON.stringify({
            idempotencyKey,
            narration: 'k6 hotspot merchant credit',
            postings: [
                {
                    accountId: accounts.retailAccountIds[
                        Math.floor(Math.random() * accounts.retailAccountIds.length)
                    ],
                    direction: 'DEBIT',
                    amount: '1.0000',
                },
                {
                    accountId: accounts.hotspotMerchantId,
                    direction: 'CREDIT',
                    amount: '1.0000',
                },
            ],
        }),
        {
            headers: {
                'Content-Type': 'application/json',
                'Idempotency-Key': idempotencyKey,
            },
        },
    );

    check(response, {
        'hotspot transaction created': (result) => result.status === 201,
    });
}

function uuidV4() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
        const random = Math.floor(Math.random() * 16);
        return (character === 'x' ? random : (random & 0x3) | 0x8).toString(16);
    });
}

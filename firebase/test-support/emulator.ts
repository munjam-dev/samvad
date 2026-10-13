export const PROJECT_ID = 'demo-samvad';
export const REGION = 'asia-south1';
export const HOSTS = {
  auth: '127.0.0.1:9099',
  firestore: { host: '127.0.0.1', port: 8080 },
  database: { host: '127.0.0.1', port: 9000 },
  storage: { host: '127.0.0.1', port: 9199 },
  functions: { host: '127.0.0.1', port: 5001 },
} as const;

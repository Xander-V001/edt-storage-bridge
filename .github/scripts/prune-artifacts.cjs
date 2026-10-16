'use strict';

/**
 * Prune build artifacts in this repository, retaining the most recent nonexpired
 * archive per known artifact name. Never touches unrelated workflow artifacts.
 * Requires GITHUB_TOKEN permissions: actions:write.
 */
module.exports = async function pruneArtifacts({ github, context, core }) {
  const { owner, repo } = context.repo;
  const names = ['edt-storage-bridge-p2', 'tycho-test-reports'];
  const artifacts = await github.paginate(github.rest.actions.listArtifactsForRepo, {
    owner,
    repo,
    per_page: 100,
  });

  for (const name of names) {
    const matching = artifacts.filter((artifact) =>
      artifact.name === name && !artifact.expired
    ).sort((left, right) =>
      Date.parse(right.created_at) - Date.parse(left.created_at) ||
      right.id - left.id
    );

    if (matching.length === 0) {
      core.info(`No artifacts found for ${name}`);
      continue;
    }

    core.info(`Keeping newest ${name} artifact ${matching[0].id}`);
    for (const artifact of matching.slice(1)) {
      await github.rest.actions.deleteArtifact({
        owner,
        repo,
        artifact_id: artifact.id,
      });
      core.info(`Deleted older ${name} artifact ${artifact.id}`);
    }
  }
};

(function () {
  'use strict';

  const CEDICT_REFERENCE_PATTERN = /([^\s,;:()\[\]\/|]*[\p{Script=Han}][^\s,;:()\[\]\/|]*)\|([^\s,;:()\[\]\/|]*[\p{Script=Han}][^\s,;:()\[\]\/|]*)(\[[^\]]+\])?/gu;

  const formatDefinition = definition =>
    definition.replace(CEDICT_REFERENCE_PATTERN, '$2$3');

  const formatDefinitions = (definitions, limit = 2) =>
    definitions.slice(0, limit).map(formatDefinition).join('; ');

  globalThis.MandopopCedictFormatter = Object.freeze({
    formatDefinition,
    formatDefinitions
  });
})();
